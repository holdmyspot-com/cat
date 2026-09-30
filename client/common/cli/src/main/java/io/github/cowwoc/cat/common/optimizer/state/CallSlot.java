package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable native-call boundary owned by one scheduler fence.
 *
 * @param logicalCallId stable logical call identity
 * @param fence owner fence that claimed the slot
 * @param state native-call boundary state
 * @param requestRef immutable request evidence
 * @param slotClaimRef original immutable claim evidence
 * @param preStartRef durable pre-start evidence
 * @param runId native run identity when native start succeeded
 * @param cancellationRequested durable cancellation intent flag
 * @param completionRef settled completion or start-failure evidence
 * @param adoptionRef evidence that the settled outcome was adopted
 */
public record CallSlot(String logicalCallId, long fence, State state, EvidenceRef requestRef,
	EvidenceRef slotClaimRef, Optional<EvidenceRef> preStartRef, Optional<String> runId, boolean cancellationRequested,
	Optional<EvidenceRef> completionRef, Optional<EvidenceRef> adoptionRef)
{
	/** Native call boundary states. */
	public enum State
	{
		CLAIMED,
		STARTING,
		ACTIVE,
		SETTLED,
		ADOPTED
	}

	/** Validates the state-dependent evidence shape. */
	public CallSlot
	{
		if (logicalCallId == null || logicalCallId.isBlank() || fence < 1)
			throw new IllegalArgumentException("Call identity or fence is invalid");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(requestRef, "requestRef");
		Objects.requireNonNull(slotClaimRef, "slotClaimRef");
		if (!slotClaimRef.collection().equals("call-slot-claims"))
			throw new IllegalArgumentException("Call slot claim has the wrong collection");
		Objects.requireNonNull(preStartRef, "preStartRef");
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(completionRef, "completionRef");
		Objects.requireNonNull(adoptionRef, "adoptionRef");
		if (state != State.CLAIMED && preStartRef.isEmpty())
			throw new IllegalArgumentException("Started slot requires pre-start evidence");
		if (state == State.ACTIVE && runId.isEmpty())
			throw new IllegalArgumentException("Active slot requires a native run id");
		if ((state == State.SETTLED || state == State.ADOPTED) && completionRef.isEmpty())
			throw new IllegalArgumentException("Settled slot requires completion evidence");
		if (state == State.ADOPTED && adoptionRef.isEmpty() ||
			state != State.ADOPTED && adoptionRef.isPresent())
			throw new IllegalArgumentException("Adoption evidence does not match slot state");
		if (runId.isPresent() && runId.orElseThrow().isBlank())
			throw new IllegalArgumentException("Native run id must be non-blank");
		if (state == State.CLAIMED && (runId.isPresent() || completionRef.isPresent()))
			throw new IllegalArgumentException("Claimed slot cannot have a native result");
		if (state == State.STARTING && (runId.isPresent() || completionRef.isPresent()))
			throw new IllegalArgumentException("Starting slot cannot have a native result");
	}
}
