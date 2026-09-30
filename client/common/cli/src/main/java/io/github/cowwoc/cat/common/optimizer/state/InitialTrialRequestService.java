package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.HostToolRequirement;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidate;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectExecutionSnapshot;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Publishes the retained native request for one assigned initial trial. */
public final class InitialTrialRequestService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds request publication to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public InitialTrialRequestService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes or retrieves the exact native request for an assigned initial trial, including its retained pause retry.
	 *
	 * @param assignmentRef committed assignment
	 * @return content-addressed native request
	 * @throws IOException if the assignment, candidate, or current campaign differs
	 */
	public EvidenceRef prepare(EvidenceRef assignmentRef) throws IOException
	{
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		if (!assignmentRef.collection().equals("trial-assignments"))
			throw new IllegalArgumentException("Initial trial request requires a trial assignment");
		CampaignBinding binding = store.binding();
		CampaignStore registered = locator.requireRegisteredBinding(binding);
		if (!registered.directory().equals(store.directory()))
			throw new IOException("Initial trial request store is not the registered campaign");
		TrialAssignment assignment;
		try
		{
			assignment = store.readTrialAssignment(assignmentRef);
		}
		catch (IOException exception)
		{
			throw new IOException("Trial assignment evidence is unavailable", exception);
		}
		Campaign campaign = store.lockedSnapshot(binding);
		if (campaign.status() != CampaignStatus.DEVELOPING ||
			campaign.measurementState() == null ||
			!assignment.campaignId().equals(binding.campaignId()) ||
			!assignment.lineage().lineageId().equals(campaign.activeLineageId()) ||
			!assignment.measurementEpochRef().equals(campaign.measurementState().epochRef()) ||
			!assignment.candidateId().equals(campaign.currentCandidateId()) ||
			campaign.trials().stream().noneMatch(trial ->
				trial.logicalTrialId().equals(assignment.logicalTrialId()) &&
				trial.selectionEvidence().equals(assignmentRef) && trial.status() == TrialStatus.ASSIGNED))
			throw new IOException("Trial assignment is not current and admitted for initial dispatch");
		store.requireIndexedCategoryDecision(assignment, campaign);
		requireMatchingPause(campaign, assignment);
		InitialCandidate candidate = new InitialCandidateService(locator, store).load();
		if (!candidate.lineageId().equals(assignment.lineage().lineageId()) ||
			!candidate.contractId().equals(assignment.lineage().contractId()) ||
			!candidate.candidateSnapshotId().equals(assignment.candidateId()))
			throw new IOException("Trial assignment does not identify the adopted initial candidate");
		locator.verifyInitialFork(binding);
		InitialTargetCaptureService sources = new InitialTargetCaptureService(locator, store);
		InitialTargetCapture capture = sources.load(binding, candidate.sourceCaptureRef());
		if (!capture.projectRoot().equals(locator.keyForBinding(binding).canonicalProjectRoot()))
			throw new IOException("Trial source capture conflicts with the registered campaign");
		EvidenceRef manifestRef = sources.publishSnapshotManifest(binding, candidate.sourceCaptureRef());
		Path project = sources.materializeProjectSnapshot(binding, candidate.sourceCaptureRef());
		ProjectRunContext context = new ProjectRunContext(new ProjectExecutionSnapshot(project,
			manifestRef), ProjectWorkspaceMode.ISOLATED_COPY, Optional.empty(), Optional.empty());
		List<HostToolRequirement> requiredTools = assignment.instance().definition().requiredHostTools().stream().
			map(name -> new HostToolRequirement(name, name)).toList();
		SessionMode sessionMode = switch (assignment.instance().definition().sessionMode())
		{
			case SINGLE_TURN -> SessionMode.SINGLE_TURN;
			case MULTI_TURN -> SessionMode.MULTI_TURN;
		};
		InitialTrialRunRequest request = new InitialTrialRunRequest(InitialTrialRunRequest.SCHEMA_VERSION,
			binding.campaignId(), assignmentRef, assignment.logicalTrialId(), context,
			assignment.instance().turns(), sessionMode, campaign.optimizationProfile(), requiredTools);
		return store.appendImmutableJson("run-requests", JSON.writeValueAsBytes(request));
	}

	/**
	 * Allows request recovery during a pause only for the trial whose before-call conditions changed.
	 *
	 * @param campaign current confirmed campaign
	 * @param assignment selected trial assignment
	 * @throws IOException if another call or boundary caused the measurement pause
	 */
	private void requireMatchingPause(Campaign campaign, TrialAssignment assignment) throws IOException
	{
		Optional<MeasurementPause> pending = campaign.measurementState().pause();
		if (pending.isEmpty())
			return;
		MeasurementPause pause = pending.orElseThrow();
		if (pause.origin() != MeasurementPause.Origin.ADMISSION ||
			pause.savedPhase() != CampaignStatus.DEVELOPING)
			throw new IOException("Measurement pause does not belong to initial trial admission");
		MeasurementChange change = store.readMeasurementChange(pause.changeRef());
		MeasurementObservation observed = store.readMeasurementObservation(change.changedRef());
		if (!change.epochRef().equals(assignment.measurementEpochRef()) ||
			!change.lineageId().equals(assignment.lineage().lineageId()) ||
			!observed.logicalCallId().equals(assignment.logicalTrialId()) ||
			observed.stage() != MeasurementObservation.Stage.BEFORE_CALL)
			throw new IOException("Measurement pause belongs to another trial assignment");
	}
}
