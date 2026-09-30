package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable claim of one native call by the original command invocation and scheduler fence.
 *
 * @param schemaVersion evidence schema version
 * @param campaignId pinned campaign identity
 * @param invocationId original command invocation identity
 * @param logicalCallId native logical call identity
 * @param claimFence original scheduler claim fence
 * @param runRequestRef immutable C0R run request
 * @param preparationOwnerRequestRef current C4 preparation owner, if applicable
 * @param preparationCycleRef current C4 preparation cycle, if applicable
 */
public record CallSlotClaimRecord(int schemaVersion, String campaignId, String invocationId,
	String logicalCallId, long claimFence, EvidenceRef runRequestRef,
	Optional<EvidenceRef> preparationOwnerRequestRef, Optional<EvidenceRef> preparationCycleRef)
{
	/** Validates the complete durable identity and collection shape. */
	public CallSlotClaimRecord
	{
		if (schemaVersion != 1 || campaignId == null || campaignId.isBlank() ||
			invocationId == null || invocationId.isBlank() || logicalCallId == null || logicalCallId.isBlank() ||
			claimFence < 1)
			throw new IllegalArgumentException("Call-slot claim identity is invalid");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		Objects.requireNonNull(preparationOwnerRequestRef, "preparationOwnerRequestRef");
		Objects.requireNonNull(preparationCycleRef, "preparationCycleRef");
		if (!runRequestRef.collection().equals("run-requests") ||
			preparationOwnerRequestRef.isPresent() != preparationCycleRef.isPresent())
			throw new IllegalArgumentException("Call-slot claim request or preparation shape is invalid");
	}
}
