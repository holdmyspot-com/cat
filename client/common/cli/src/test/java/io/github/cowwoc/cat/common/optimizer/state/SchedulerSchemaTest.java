package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

/** Tests schema-level fence and evidence identity invariants. */
public final class SchedulerSchemaTest
{
	/** Creates the test fixture. */
	public SchedulerSchemaTest()
	{
	}

	/** A future call slot cannot be embedded under an older scheduler fence. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsFutureCallSlotFence()
	{
		CallSlot future = new CallSlot("call", 2, CallSlot.State.CLAIMED,
			new EvidenceRef("run-requests", "a".repeat(64)),
			new EvidenceRef("call-slot-claims", "b".repeat(64)), Optional.empty(), Optional.empty(),
			false, Optional.empty(), Optional.empty());
		new SchedulerState(1, List.of(), List.of(), List.of(future), Optional.empty());
	}

	/** Zero is not a valid observed owner fence. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsZeroObservedFence()
	{
		new InvocationStopRegistration(new EvidenceRef("invocation-stop-registrations", "a".repeat(64)),
			new CampaignBinding("campaign", Path.of("/tmp/campaign")), "invocation", 0,
			Optional.of(0L), Optional.empty(), InvocationStopRegistration.Status.OPEN, Optional.empty());
	}

	/** Zero is not a valid stop source fence. */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsZeroStopFence()
	{
		new StopRequest(new EvidenceRef("stop-requests", "a".repeat(64)), "invocation",
			Optional.of(0L), "token", StopRequest.Status.PENDING, Optional.empty(), Optional.empty(),
			Optional.empty());
	}
}
