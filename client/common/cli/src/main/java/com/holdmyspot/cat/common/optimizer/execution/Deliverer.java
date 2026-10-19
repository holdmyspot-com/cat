package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
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
 * {@link Delivery#deliver(String, DeliveryRequest, Overlay, Overlay, Set, CertificationRecord, List)}.
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
	 * @param record the certification record to write, non-null exactly when {@code request} is labelled
	 *   {@code certified}
	 * @param tests the confirmed prompt tests to write, empty unless {@code request} is labelled {@code certified} or
	 *   the user kept the incumbent
	 * @return the result
	 * @throws NullPointerException if {@code campaignId}, {@code selected}, {@code working}, {@code promptSources}
	 *   or {@code tests} are null
	 * @throws IOException if a file cannot be read or written
	 */
	DeliveryResult deliver(String campaignId, DeliveryRequest request, Overlay selected, Overlay working,
		Set<Path> promptSources, CertificationRecord record, List<DeliveredTest> tests) throws IOException;
}
