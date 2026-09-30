package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Observes and claims a prepared private generation call before native dispatch.
 */
public final class CandidateGenerationAdmissionService
{
	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds admission to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public CandidateGenerationAdmissionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Retains observed conditions and claims the exact generation request when the observer accepts them.
	 *
	 * @param lease exclusive scheduler owner
	 * @param semanticRequestRef indexed private generation request
	 * @param conditions observed evidence or an explicit limitation for every material condition
	 * @return retained claim or material-change pause
	 * @throws IOException if the campaign, request, or observation conflicts with current authority
	 */
	public CandidateGenerationAdmission admit(SchedulerLease lease, EvidenceRef semanticRequestRef,
		MeasurementConditions conditions) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		Objects.requireNonNull(conditions, "conditions");
		lease.requireLive();
		if (!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Candidate admission store is not the registered campaign");
		CandidateGenerationRequest request = new CandidateGenerationRequestService(locator, store).
			read(semanticRequestRef);
		CandidateGenerationRunRequestService requests = new CandidateGenerationRunRequestService(locator, store);
		EvidenceRef requestRef = requests.prepare(semanticRequestRef);
		MeasurementObservationDecision observation = locator.openMeasurementObserver(lease.binding()).
			beforeCall(lease, request.logicalCallId(), conditions);
		if (observation.status() == MeasurementObservationDecision.Status.PAUSED)
			return new CandidateGenerationAdmission.Paused(requestRef, observation.observationRef(),
				observation.changeRef().orElseThrow());
		ClaimedSlot claim = locator.openSchedulerLease(lease.binding()).claimOrResumeCall(lease,
			request.logicalCallId(), requestRef, Optional.empty(), Optional.empty());
		return new CandidateGenerationAdmission.Claimed(requestRef, observation.observationRef(), claim);
	}
}
