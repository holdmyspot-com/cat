package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.runner.ActiveCall;
import java.io.IOException;
import java.util.Optional;

/**
 * Reconciles an active native handle with its original scheduler start and current cancellation state.
 */
final class RetainedNativeCallRecovery
{
	/** Prevents instantiation. */
	private RetainedNativeCallRecovery()
	{
	}

	/**
	 * Accepts the original run, activates a start owned by the current lease, and honors retained cancellation.
	 * A successor owner preserves an older starting slot until its completion can be adopted.
	 * Rejected handles are interrupted and closed before the failure propagates.
	 *
	 * @param lease current scheduler owner
	 * @param store pinned campaign store
	 * @param claimed verified original slot and claim
	 * @param preStart verified indexed run identity
	 * @param recovered caller-owned active handle
	 * @throws IOException if run identity or scheduler ownership conflicts
	 */
	static void acceptActiveCall(SchedulerLease lease, CampaignStore store, ClaimedSlot claimed,
		CallPreStartRecord preStart, ActiveCall recovered) throws IOException
	{
		try
		{
			CallSlot slot = claimed.slot();
			String callId = slot.logicalCallId();
			if (!recovered.runId().equals(preStart.runId()))
				throw new IOException("Recovered native run differs from the retained pre-start");
			if (slot.state() == CallSlot.State.STARTING)
			{
				if (slot.fence() == lease.fence())
					lease.activate(callId, recovered.runId());
				else if (slot.fence() > lease.fence())
					throw new IOException("Retained native start belongs to a newer scheduler owner");
			}
			else if (slot.state() != CallSlot.State.ACTIVE ||
				!slot.runId().equals(Optional.of(recovered.runId())))
				throw new IOException("Retained active slot differs from the recovered native run");
			if (store.readCallSlotClaim(lease.binding(), callId).slot().cancellationRequested())
				recovered.interrupt();
		}
		catch (IOException | RuntimeException failure)
		{
			NativeCallHandleCleanup.interruptAndClose(recovered, failure);
			throw failure;
		}
	}
}
