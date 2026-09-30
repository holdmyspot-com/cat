package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Observes and claims a frozen evaluator call after its trial has been adopted. */
public final class InitialEvaluatorAdmissionService
{
	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds evaluator admission to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public InitialEvaluatorAdmissionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Observes current trial conditions and claims the assigned evaluator call.
	 *
	 * @param lease exclusive scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @param artifact selected packaged runner artifact
	 * @return retained claim or durable known-change pause
	 * @throws IOException if the trial, observation, or runner evidence conflicts
	 */
	public InitialEvaluatorAdmission admit(SchedulerLease lease, EvidenceRef assignmentRef,
		Path artifact) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		Objects.requireNonNull(artifact, "artifact");
		lease.requireLive();
		if (!lease.binding().realDirectory().equals(store.directory()) ||
			!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Evaluator admission belongs to another campaign store");
		InitialEvaluatorRequestService requests = new InitialEvaluatorRequestService(locator, store);
		EvidenceRef requestRef = requests.prepare(assignmentRef);
		InitialEvaluatorRunRequest request = requests.readRunRequest(requestRef);
		InitialTrialCompletionService.RetainedTrialCall trial =
			new InitialTrialCompletionService(locator, store).load(lease, assignmentRef);
		TrialAssignment assignment = trial.assignment();
		if (trial.claimed().slot().state() != CallSlot.State.ADOPTED ||
			!request.campaignId().equals(lease.binding().campaignId()) ||
			!request.logicalCallId().equals(assignment.logicalTrialId() + "-evaluator"))
			throw new IOException("Evaluator request conflicts with its adopted trial");
		InitialEvaluatorRequest semantic = requests.readSemanticRequest(request.semanticRequestRef());
		if (!semantic.campaignId().equals(lease.binding().campaignId()) ||
			!semantic.assignmentRef().equals(assignmentRef) ||
			!semantic.lineage().equals(assignment.lineage()))
			throw new IOException("Evaluator semantic request conflicts with its assigned trial");
		MeasurementConditions conditions = new InitialTrialAdmissionService(locator, store).
			conditions(assignment, trial.request(), artifact);
		MeasurementObservationDecision observation = locator.openMeasurementObserver(lease.binding()).
			beforeCall(lease, request.logicalCallId(), conditions);
		if (observation.status() == MeasurementObservationDecision.Status.PAUSED)
			return new InitialEvaluatorAdmission.Paused(requestRef, observation.observationRef(),
				observation.changeRef().orElseThrow());
		ClaimedSlot claim = locator.openSchedulerLease(lease.binding()).claimOrResumeCall(lease,
			request.logicalCallId(), requestRef, Optional.empty(), Optional.empty());
		return new InitialEvaluatorAdmission.Claimed(requestRef, observation.observationRef(), claim);
	}
}
