package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveredTest;
import io.github.cowwoc.cat.common.optimizer.delivery.Delivery;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Delivers a candidate that a campaign decided to deliver, or the prompt tests of an incumbent that the user kept, for
 * the {@link Driver}.
 * <p>
 * Production passes {@link Delivery#deliver(String, Optional, Overlay, Overlay, Set, Optional, List)}.
 */
@FunctionalInterface
public interface Deliverer
{
	/**
	 * Delivers a candidate, or the prompt tests of a kept incumbent.
	 *
	 * @param campaignId the campaign's identifier
	 * @param request what the campaign decided to deliver, or empty if the user kept the incumbent
	 * @param selected the overlay of the candidate that {@code request} names, or the incumbent's if the user kept it
	 * @param working the working candidate's overlay
	 * @param promptSources the participating prompt sources
	 * @param record the certification record to write, present exactly when {@code request} is labelled
	 *   {@code certified}
	 * @param tests the confirmed prompt tests to write, empty unless {@code request} is labelled {@code certified} or
	 *   the user kept the incumbent
	 * @return the result
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if a file cannot be read or written
	 */
	DeliveryResult deliver(String campaignId, Optional<DeliveryRequest> request, Overlay selected, Overlay working,
		Set<Path> promptSources, Optional<CertificationRecord> record, List<DeliveredTest> tests) throws IOException;
}
