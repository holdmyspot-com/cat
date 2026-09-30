package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import java.util.Objects;
import java.util.Optional;

/**
 * Binds a producer's retained attempt to a claimed slot before native dispatch.
 *
 * @param schemaVersion exactly three
 * @param logicalCallId the scheduler call named by the producer
 * @param requestRef the immutable native request
 * @param slotClaimRef the original scheduler claim
 * @param preparationOwnerRequestRef the owner request for contract preparation, if applicable
 * @param preparationCycleRef the contract preparation cycle, if applicable
 * @param beforeCallObservationRef the confirmed call's indexed measurement observation, if applicable
 * @param producerAttemptKind the producer that owns the attempt
 * @param producerAttemptRef the immutable owner attempt with a matching common header
 * @param ownerRequestRef the immutable request named by the attempt header
 * @param runId the preallocated native run identity retained before dispatch
 * @param runtimeManifestSha256 the verified packaged runner identity selected before dispatch
 */
public record CallPreStartRecord(int schemaVersion, String logicalCallId, EvidenceRef requestRef,
	EvidenceRef slotClaimRef, Optional<EvidenceRef> preparationOwnerRequestRef,
	Optional<EvidenceRef> preparationCycleRef, Optional<EvidenceRef> beforeCallObservationRef,
	ProducerAttemptKind producerAttemptKind,
	EvidenceRef producerAttemptRef, EvidenceRef ownerRequestRef, String runId, String runtimeManifestSha256)
{
	/**
	 * Validates the retained pre-start identity and preparation shape.
	 *
	 * @param schemaVersion exactly three
	 * @param logicalCallId the scheduler call named by the producer
	 * @param requestRef the immutable native request
	 * @param slotClaimRef the original scheduler claim
	 * @param preparationOwnerRequestRef the owner request for contract preparation, if applicable
	 * @param preparationCycleRef the contract preparation cycle, if applicable
	 * @param beforeCallObservationRef the confirmed call's indexed measurement observation, if applicable
	 * @param producerAttemptKind the producer that owns the attempt
	 * @param producerAttemptRef the immutable owner attempt with a matching common header
	 * @param ownerRequestRef the immutable request named by the attempt header
	 * @param runId the preallocated native run identity
	 * @param runtimeManifestSha256 the verified packaged runner identity
	 * @throws NullPointerException if a required value or container is null
	 * @throws IllegalArgumentException if the record has an invalid schema or evidence shape
	 */
	public CallPreStartRecord
	{
		if (schemaVersion != 3 || logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Invalid call pre-start identity");
		Objects.requireNonNull(requestRef, "requestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(preparationOwnerRequestRef, "preparationOwnerRequestRef");
		Objects.requireNonNull(preparationCycleRef, "preparationCycleRef");
		Objects.requireNonNull(beforeCallObservationRef, "beforeCallObservationRef");
		Objects.requireNonNull(producerAttemptKind, "producerAttemptKind");
		Objects.requireNonNull(producerAttemptRef, "producerAttemptRef");
		Objects.requireNonNull(ownerRequestRef, "ownerRequestRef");
		HarnessRun.requireValidId(runId);
		Objects.requireNonNull(runtimeManifestSha256, "runtimeManifestSha256");
		if (!runtimeManifestSha256.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Invalid pre-start runner runtime SHA-256");
		if (!requestRef.collection().equals("run-requests") ||
			!slotClaimRef.collection().equals("call-slot-claims") ||
			preparationOwnerRequestRef.isPresent() != preparationCycleRef.isPresent() ||
			beforeCallObservationRef.isPresent() && !beforeCallObservationRef.orElseThrow().collection().
				equals("dynamic-baseline-roots"))
			throw new IllegalArgumentException("Invalid call pre-start request, claim or preparation shape");
	}
}
