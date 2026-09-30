package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Rehashed claim retained by exactly one durable call slot.
 *
 * @param slotClaimRef authoritative immutable claim reference
 * @param slot durable call slot
 * @param claim original immutable claim record
 */
public record ClaimedSlot(EvidenceRef slotClaimRef, CallSlot slot, CallSlotClaimRecord claim)
{
	/** Requires the slot and its immutable claim to identify each other. */
	public ClaimedSlot
	{
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		Objects.requireNonNull(slot, "slot");
		Objects.requireNonNull(claim, "claim");
		if (!slotClaimRef.equals(slot.slotClaimRef()) || !slot.logicalCallId().equals(claim.logicalCallId()) ||
			!slot.requestRef().equals(claim.runRequestRef()) || slot.fence() < claim.claimFence())
			throw new IllegalArgumentException("Call slot does not retain its exact claim");
	}
}
