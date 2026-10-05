package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.delivery.Delivery;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Delivers a candidate that a campaign decided to deliver, for the {@link Driver}.
 * <p>
 * Production passes {@link Delivery#deliver(String, DeliveryRequest, Overlay, Overlay, Set, Optional)}.
 */
@FunctionalInterface
public interface Deliverer
{
	/**
	 * Delivers a candidate.
	 *
	 * @param campaignId the campaign's identifier
	 * @param request what the campaign decided to deliver
	 * @param selected the overlay of the candidate that {@code request} names
	 * @param working the working candidate's overlay
	 * @param promptSources the participating prompt sources
	 * @param record the certification record to write, present exactly when {@code request} is labelled
	 *   {@code certified}
	 * @return the result
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if a file cannot be read or written
	 */
	DeliveryResult deliver(String campaignId, DeliveryRequest request, Overlay selected, Overlay working,
		Set<Path> promptSources, Optional<CertificationRecord> record) throws IOException;
}
