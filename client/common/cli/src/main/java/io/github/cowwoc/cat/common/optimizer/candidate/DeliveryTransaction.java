package io.github.cowwoc.cat.common.optimizer.candidate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Versioned durable transaction state after one journal sequence.
 *
 * @param schemaVersion exact durable schema
 * @param transactionId stable transaction identity
 * @param journalSequence contiguous journal sequence
 * @param kind mutating or no-op
 * @param phase durable transaction phase
 * @param baselineId initial target snapshot
 * @param previousAppliedCandidateId predecessor snapshot when beyond baseline
 * @param selectedCandidateId selected snapshot
 * @param confirmedRoots exact confirmed editable roots
 * @param directories transaction directory plan
 * @param paths ordered user-visible path plan
 */
public record DeliveryTransaction(int schemaVersion, String transactionId, long journalSequence, DeliveryKind kind,
	DeliveryPhase phase, String baselineId, Optional<String> previousAppliedCandidateId, String selectedCandidateId,
	List<EditableRoot> confirmedRoots, List<DeliveryDirectory> directories, List<DeliveryPath> paths)
{
	/** The only supported durable delivery transaction schema. */
	public static final int SCHEMA_VERSION = 2;

	/** Validates the journal state and complete path plan. */
	public DeliveryTransaction
	{
		if (schemaVersion != SCHEMA_VERSION || transactionId == null ||
			!transactionId.matches("[a-z0-9-]{1,100}") ||
			journalSequence < 0)
			throw new IllegalArgumentException("Delivery transaction schema, identity, or sequence is invalid");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(phase, "phase");
		Objects.requireNonNull(previousAppliedCandidateId, "previousAppliedCandidateId");
		if (!PromptSnapshot.isSha256(baselineId) || !PromptSnapshot.isSha256(selectedCandidateId) ||
			previousAppliedCandidateId.isPresent() &&
				!PromptSnapshot.isSha256(previousAppliedCandidateId.orElseThrow()))
			throw new IllegalArgumentException("Delivery transaction candidate identity is invalid");
		confirmedRoots = new DeliveryWriteAuthority(confirmedRoots).roots();
		directories = List.copyOf(Objects.requireNonNull(directories, "directories"));
		paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
		if (kind == DeliveryKind.NO_OP && (!paths.isEmpty() || !directories.isEmpty()))
			throw new IllegalArgumentException("NO_OP transaction cannot contain paths or directories");
		if (kind == DeliveryKind.MUTATING && paths.isEmpty())
			throw new IllegalArgumentException("MUTATING transaction requires a path");
		DeliveryWriteAuthority authority = new DeliveryWriteAuthority(confirmedRoots);
		for (int index = 0; index < paths.size(); ++index)
		{
			DeliveryPath path = paths.get(index);
			if (path.ordinal() != index || !authority.permits(path.target()))
				throw new IllegalArgumentException("Delivery path order or confirmed authority is invalid");
			if (!path.selectedStage().equals(DeliveryPath.privateSibling(path.target(), transactionId, index,
				"stage")) || !path.predecessorCapture().equals(DeliveryPath.privateSibling(path.target(), transactionId,
				index, "predecessor")) || !path.rollbackCapture().equals(DeliveryPath.privateSibling(path.target(),
				transactionId, index, "rollback")))
				throw new IllegalArgumentException("Delivery private sibling names differ from transaction-owned plan");
		}
	}
}
