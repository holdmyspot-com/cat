package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * A {@link Deliverer} that records each delivery and returns scripted results, by default a delivery that changed
 * nothing in the base worktree.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RecordingDeliverer implements Deliverer
{
	private final Object lock = new Object();
	private final Deque<DeliveryResult> scripted = new ArrayDeque<>();
	private final List<Call> calls = new ArrayList<>();

	/**
	 * Creates a deliverer that has delivered nothing.
	 */
	public RecordingDeliverer()
	{
	}

	/**
	 * Queues the result of the next delivery.
	 *
	 * @param result the result
	 */
	public void respond(DeliveryResult result)
	{
		synchronized (lock)
		{
			scripted.add(result);
		}
	}

	@Override
	public DeliveryResult deliver(String campaignId, DeliveryRequest request, Overlay selected,
		Overlay working, Set<Path> promptSources)
	{
		synchronized (lock)
		{
			calls.add(new Call(request, selected, working, promptSources));
			DeliveryResult result = scripted.poll();
			if (result != null)
				return result;
			return new DeliveryResult.Delivered(request.label(), new Applicability(Applicability.Status.CURRENT, false,
				List.of()), List.of(), List.of(), List.of(), "");
		}
	}

	/**
	 * Returns the deliveries made so far.
	 *
	 * @return the deliveries, in call order
	 */
	public List<Call> calls()
	{
		synchronized (lock)
		{
			return List.copyOf(calls);
		}
	}

	/**
	 * One delivery.
	 *
	 * @param request the delivery request
	 * @param selected the selected candidate's overlay
	 * @param working the working candidate's overlay
	 * @param promptSources the prompt sources and authorized targets that the delivery checks applicability against
	 */
	public record Call(DeliveryRequest request, Overlay selected, Overlay working, Set<Path> promptSources)
	{
	}
}
