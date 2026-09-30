package io.github.cowwoc.cat.common.optimizer.candidate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Archived immutable result of a terminal delivery transaction.
 *
 * @param schemaVersion durable receipt schema
 * @param transactionId transaction identity
 * @param baselineId initial target snapshot identity
 * @param previousAppliedCandidateId predecessor identity when beyond baseline
 * @param selectedCandidateId selected candidate identity
 * @param outcome terminal result
 * @param paths ordered per-path results
 * @param finalTransactionSha256 exact final transaction journal hash
 */
public record DeliveryReceipt(int schemaVersion, String transactionId, String baselineId,
	Optional<String> previousAppliedCandidateId, String selectedCandidateId, DeliveryReceiptOutcome outcome,
	List<DeliveryPathReceipt> paths, String finalTransactionSha256)
{
	/** The only supported receipt schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates the archived receipt identity and outcome shape. */
	public DeliveryReceipt
	{
		if (schemaVersion != SCHEMA_VERSION || transactionId == null ||
			!transactionId.matches("[a-z0-9-]{1,100}"))
			throw new IllegalArgumentException("Delivery receipt schema or transaction identity is invalid");
		Objects.requireNonNull(previousAppliedCandidateId, "previousAppliedCandidateId");
		Objects.requireNonNull(outcome, "outcome");
		paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
		if (!PromptSnapshot.isSha256(baselineId) || !PromptSnapshot.isSha256(selectedCandidateId) ||
			!PromptSnapshot.isSha256(finalTransactionSha256) || previousAppliedCandidateId.isPresent() &&
				!PromptSnapshot.isSha256(previousAppliedCandidateId.orElseThrow()))
			throw new IllegalArgumentException("Delivery receipt content identity is invalid");
		if (outcome == DeliveryReceiptOutcome.ALREADY_APPLIED && !paths.isEmpty())
			throw new IllegalArgumentException("ALREADY_APPLIED receipt must have zero paths");
	}
}
