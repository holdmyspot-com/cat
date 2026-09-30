package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Carries the call identity shared by inspection, generation, evaluation, trial, and advice attempts.
 *
 * @param kind the producer that owns the attempt
 * @param logicalCallId the scheduler call named by the attempt
 * @param runRequestRef the immutable native request used by that call
 * @param slotClaimRef the original scheduler claim retained across fence transfer
 * @param ownerRequestRef the immutable request that owns this producer attempt
 * @param preparationCycleRef the contract preparation cycle for inspection or derivation
 */
public record ProducerAttemptHeader(ProducerAttemptKind kind, String logicalCallId,
	EvidenceRef runRequestRef, EvidenceRef slotClaimRef, EvidenceRef ownerRequestRef,
	Optional<EvidenceRef> preparationCycleRef)
{
	/**
	 * Validates the common identity before an owner retains an attempt.
	 *
	 * @param kind the producer that owns the attempt
	 * @param logicalCallId the scheduler call named by the attempt
	 * @param runRequestRef the immutable native request used by that call
	 * @param slotClaimRef the original scheduler claim retained across fence transfer
	 * @param ownerRequestRef the immutable request that owns this producer attempt
	 * @param preparationCycleRef the contract preparation cycle for inspection or derivation
	 * @throws NullPointerException if a required value or container is null
	 * @throws IllegalArgumentException if the call or evidence collection is invalid
	 */
	public ProducerAttemptHeader
	{
		Objects.requireNonNull(kind, "kind");
		if (logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("logicalCallId must not be blank");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(ownerRequestRef, "ownerRequestRef");
		Objects.requireNonNull(preparationCycleRef, "preparationCycleRef");
		if (!runRequestRef.collection().equals("run-requests") ||
			!slotClaimRef.collection().equals("call-slot-claims"))
			throw new IllegalArgumentException("Attempt header has an invalid request or claim collection");
	}
}
