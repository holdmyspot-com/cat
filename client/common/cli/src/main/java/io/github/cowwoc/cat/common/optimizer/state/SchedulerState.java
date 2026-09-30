package io.github.cowwoc.cat.common.optimizer.state;

import java.util.List;
import java.util.Optional;

/**
 * Atomic scheduler metadata embedded in one campaign commit record.
 *
 * @param fence last durable owner fence
 * @param invocations exact command invocation registrations
 * @param stops retained stop requests
 * @param slots durable native-call boundaries
 * @param barrier current purpose-bound scheduling barrier
 */
public record SchedulerState(long fence, List<InvocationStopRegistration> invocations, List<StopRequest> stops,
	List<CallSlot> slots, Optional<SchedulerBarrier> barrier)
{
	/**
	 * Validates and copies embedded scheduler collections.
	 */
	public SchedulerState
	{
		if (fence < 0)
			throw new IllegalArgumentException("Scheduler fence must not be negative");
		invocations = List.copyOf(invocations);
		stops = List.copyOf(stops);
		slots = List.copyOf(slots);
		if (barrier == null)
			throw new NullPointerException("barrier");
		if (invocations.stream().map(InvocationStopRegistration::invocationId).distinct().count() !=
			invocations.size())
			throw new IllegalArgumentException("Scheduler has duplicate invocation identities");
		if (slots.stream().map(CallSlot::logicalCallId).distinct().count() != slots.size())
			throw new IllegalArgumentException("Scheduler has duplicate logical call identities");
		if (slots.stream().anyMatch(slot -> slot.fence() > fence) ||
			invocations.stream().anyMatch(registration ->
				registration.boundFence().isPresent() && registration.boundFence().orElseThrow() > fence ||
				registration.observedOwnerFence().isPresent() &&
					registration.observedOwnerFence().orElseThrow() > fence) ||
			stops.stream().anyMatch(stop ->
				stop.sourceFence().isPresent() && stop.sourceFence().orElseThrow() > fence ||
				stop.adoptingFence().isPresent() && stop.adoptingFence().orElseThrow() > fence) ||
			barrier.isPresent() && barrier.orElseThrow().schedulerFence() > fence)
			throw new IllegalArgumentException("Scheduler contains a future owner fence");
	}

	/**
	 * Returns an empty initial scheduler state.
	 *
	 * @return initial scheduler state
	 */
	public static SchedulerState initial()
	{
		return new SchedulerState(0, List.of(), List.of(), List.of(), Optional.empty());
	}

	/**
	 * Reports whether any call still awaits adoption, including a settled native result.
	 * This query examines the retained states; it does not validate their evidence or authorize a transition.
	 *
	 * @return whether at least one slot has not reached adoption
	 */
	boolean hasUnadoptedCalls()
	{
		return slots.stream().anyMatch(slot -> slot.state() != CallSlot.State.ADOPTED);
	}
}
