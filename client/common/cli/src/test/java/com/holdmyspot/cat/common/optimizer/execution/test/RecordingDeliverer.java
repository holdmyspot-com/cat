package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveredTest;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.execution.Deliverer;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
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
	public DeliveryResult deliver(String campaignId, DeliveryRequest request, Overlay selected, Overlay working,
		Set<Path> promptSources, CertificationRecord record, List<DeliveredTest> tests)
	{
		synchronized (lock)
		{
			calls.add(new Call(Optional.ofNullable(request), selected, working, promptSources,
				Optional.ofNullable(record), tests));
			DeliveryResult result = scripted.poll();
			if (result != null)
				return result;
			Outcome label = null;
			if (request != null)
				label = request.label();
			return new DeliveryResult.Delivered(label,
				new Applicability(Applicability.Status.CURRENT, false, List.of()), List.of(), List.of(), List.of(), "");
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
	 * @param request the delivery request, or empty if the user kept the incumbent
	 * @param selected the selected candidate's overlay, or the incumbent's if the user kept it
	 * @param working the working candidate's overlay
	 * @param promptSources the prompt sources and read-only external participants that the delivery checks
	 *   applicability against
	 * @param record the certification record that the delivery writes, or empty if the delivery is not certified
	 * @param tests the prompt tests that the delivery writes
	 */
	public record Call(Optional<DeliveryRequest> request, Overlay selected, Overlay working, Set<Path> promptSources,
		Optional<CertificationRecord> record, List<DeliveredTest> tests)
	{
	}
}
