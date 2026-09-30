package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Checks outstanding-call classification without replaying native lifecycle operations. */
public final class SchedulerStateTest
{
	/** Creates the test fixture. */
	public SchedulerStateTest()
	{
	}

	/** An empty scheduler and an adopted call have no outstanding adoption. */
	@Test
	public void recognizesCompletedAdoption()
	{
		Assert.assertFalse(SchedulerState.initial().hasUnadoptedCalls());
		CallSlot adopted = slot("adopted", CallSlot.State.ADOPTED);
		Assert.assertFalse(scheduler(List.of(adopted)).hasUnadoptedCalls());
	}

	/** Every earlier native state blocks, including one outstanding call after an adopted call. */
	@Test
	public void detectsEachUnadoptedState()
	{
		CallSlot adopted = slot("adopted", CallSlot.State.ADOPTED);
		for (CallSlot.State state : CallSlot.State.values())
		{
			if (state == CallSlot.State.ADOPTED)
				continue;
			CallSlot outstanding = slot("outstanding", state);
			Assert.assertTrue(scheduler(List.of(adopted, outstanding)).hasUnadoptedCalls(), state.name());
		}
	}

	/**
	 * Wraps typed states without establishing native evidence authority.
	 *
	 * @param slots the states being classified
	 * @return scheduler snapshot with one valid owner fence
	 */
	private static SchedulerState scheduler(List<CallSlot> slots)
	{
		return new SchedulerState(1, List.of(), List.of(), slots, Optional.empty());
	}

	/**
	 * Supplies the minimum typed evidence identities required by a native boundary state.
	 *
	 * @param callId distinct logical call identity
	 * @param state state being classified
	 * @return typed slot; the referenced artifacts are not needed by this query
	 */
	private static CallSlot slot(String callId, CallSlot.State state)
	{
		String digest = "a".repeat(64);
		Optional<EvidenceRef> preStart = Optional.empty();
		if (state != CallSlot.State.CLAIMED)
			preStart = Optional.of(new EvidenceRef("call-pre-starts", digest));
		Optional<String> runId = Optional.empty();
		if (state == CallSlot.State.ACTIVE)
			runId = Optional.of("native-run");
		Optional<EvidenceRef> completion = Optional.empty();
		if (state == CallSlot.State.SETTLED || state == CallSlot.State.ADOPTED)
			completion = Optional.of(new EvidenceRef("native-run-completions", digest));
		Optional<EvidenceRef> adoption = Optional.empty();
		if (state == CallSlot.State.ADOPTED)
			adoption = Optional.of(new EvidenceRef("generation-outcomes", digest));
		return new CallSlot(callId, 1, state, new EvidenceRef("run-requests", digest),
			new EvidenceRef("call-slot-claims", digest), preStart, runId, false, completion, adoption);
	}
}
