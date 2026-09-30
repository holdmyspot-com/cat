package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable accepted generation edit, published only after exact live verification.
 *
 * @param schemaVersion exact receipt schema
 * @param checkpointRef registered live checkpoint
 * @param logicalCallId C1 logical call
 * @param operationId call-local idempotency key
 * @param nativeToolCallRef retained C3 tool call
 * @param kind exact requested edit kind
 * @param beforeStates ordered inspected endpoint prestates
 * @param afterStates ordered verified endpoint poststates
 * @param affectedPaths ordered mutation endpoints
 * @param transactionRef immutable edit transaction evidence
 */
public record GenerationEditReceipt(int schemaVersion, EvidenceRef checkpointRef, String logicalCallId,
	String operationId, EvidenceRef nativeToolCallRef, GenerationEditOperation.Kind kind,
	List<GenerationPathState> beforeStates, List<GenerationPathState> afterStates,
	List<Path> affectedPaths, EvidenceRef transactionRef)
{
	/** The only supported edit-receipt schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates complete accepted evidence. */
	public GenerationEditReceipt
	{
		if (schemaVersion != SCHEMA_VERSION || logicalCallId == null || logicalCallId.isBlank() ||
			operationId == null || operationId.isBlank())
			throw new IllegalArgumentException("Generation edit receipt identity is invalid");
		Objects.requireNonNull(checkpointRef, "checkpointRef");
		Objects.requireNonNull(nativeToolCallRef, "nativeToolCallRef");
		Objects.requireNonNull(transactionRef, "transactionRef");
		Objects.requireNonNull(kind, "kind");
		if (!checkpointRef.collection().equals("live-generation-checkpoints") ||
			!nativeToolCallRef.collection().equals("generation-tool-calls") ||
			!transactionRef.collection().equals("generation-edit-transactions"))
			throw new IllegalArgumentException("Generation edit receipt reference collection is invalid");
		beforeStates = List.copyOf(Objects.requireNonNull(beforeStates, "beforeStates"));
		afterStates = List.copyOf(Objects.requireNonNull(afterStates, "afterStates"));
		affectedPaths = List.copyOf(Objects.requireNonNull(affectedPaths, "affectedPaths"));
		if (beforeStates.isEmpty() || beforeStates.size() != afterStates.size() ||
			!beforeStates.stream().map(GenerationPathState::path).toList().equals(affectedPaths) ||
			!afterStates.stream().map(GenerationPathState::path).toList().equals(affectedPaths))
			throw new IllegalArgumentException("Generation edit receipt endpoint states are incomplete");
	}
}
