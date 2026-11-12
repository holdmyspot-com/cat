package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
import com.holdmyspot.cat.common.optimizer.campaign.HistoryEntry;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveredTest;
import com.holdmyspot.cat.common.optimizer.delivery.Delivery;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Delivers a candidate that a campaign decided to deliver, or the prompt tests of an incumbent that the user kept, for
 * the {@link Driver}.
 * <p>
 * Production passes
 * {@link Delivery#deliver(String, DeliveryRequest, Overlay, Overlay, Set, HistoryEntry, List)}.
 */
@FunctionalInterface
public interface Deliverer
{
	/**
	 * Delivers a candidate, or the prompt tests of a kept incumbent.
	 *
	 * @param campaignId the campaign's identifier
	 * @param request what the campaign decided to deliver, or null if the user kept the incumbent
	 * @param selected the overlay of the candidate that {@code request} names, or the incumbent's if the user kept it
	 * @param working the working candidate's overlay
	 * @param promptSources the participating prompt sources
	 * @param entry the entry to append to the target's certification history: the certification record of a certified
	 *   request, or a delivery entry with the request's label, or labelled {@code incumbent kept} if the user kept the
	 *   incumbent
	 * @param tests the confirmed prompt tests to write, empty unless {@code request} is labelled {@code certified} or
	 *   the user kept the incumbent
	 * @return the result
	 * @throws NullPointerException if {@code campaignId}, {@code selected}, {@code working}, {@code promptSources},
	 *   {@code entry} or {@code tests} are null
	 * @throws IOException if a file cannot be read or written
	 */
	DeliveryResult deliver(String campaignId, DeliveryRequest request, Overlay selected, Overlay working,
		Set<Path> promptSources, HistoryEntry entry, List<DeliveredTest> tests) throws IOException;
}
