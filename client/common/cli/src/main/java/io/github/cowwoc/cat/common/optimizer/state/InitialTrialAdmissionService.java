package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshot;
import io.github.cowwoc.cat.common.optimizer.candidate.PromptSnapshotRepository;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.runner.RunnerRuntimeManifest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Observes and claims an assigned initial trial before native dispatch. */
public final class InitialTrialAdmissionService
{
	private final CampaignLocator locator;
	private final CampaignStore store;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Binds call admission to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public InitialTrialAdmissionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Observes known conditions and claims the exact assigned initial trial when they remain applicable.
	 *
	 * @param lease exclusive scheduler owner
	 * @param assignmentRef committed initial development assignment
	 * @param artifact selected packaged runner artifact
	 * @return retained claim or durable known-change pause
	 * @throws IOException if campaign, assignment, observation, or runner evidence conflicts
	 */
	public InitialTrialAdmission admit(SchedulerLease lease, EvidenceRef assignmentRef, Path artifact)
		throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		Objects.requireNonNull(artifact, "artifact");
		lease.requireLive();
		if (!lease.binding().realDirectory().equals(store.directory()))
			throw new IOException("Initial trial admission belongs to another campaign store");
		EvidenceRef requestRef = new InitialTrialRequestService(locator, store).prepare(assignmentRef);
		byte[] bytes = store.readImmutableJson(requestRef);
		InitialTrialRunRequest request;
		try
		{
			request = JSON.readValue(bytes, InitialTrialRunRequest.class);
			if (request == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(request)) ||
				!request.assignmentRef().equals(assignmentRef))
				throw new IOException("Initial trial request differs from its retained assignment");
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Initial trial request is malformed", exception);
		}
		TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(
			assignment.lineage().contractId());
		DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(store).readCurrent();
		DevelopmentWeaknessGuard.requireNoClearlyWeakCategory(evidence, contract);
		new DevelopmentReviewService(store).retainDueAndRequireEligible(evidence, contract);
		MeasurementConditions conditions = conditions(assignment, request, artifact);
		MeasurementObservationDecision observation = locator.openMeasurementObserver(lease.binding()).
			beforeCall(lease, request.logicalCallId(), conditions);
		if (observation.status() == MeasurementObservationDecision.Status.PAUSED)
			return new InitialTrialAdmission.Paused(requestRef, observation.observationRef(),
				observation.changeRef().orElseThrow());
		ClaimedSlot claim = locator.openSchedulerLease(lease.binding()).claimOrResumeCall(lease,
			request.logicalCallId(), requestRef, Optional.empty(), Optional.empty());
		return new InitialTrialAdmission.Claimed(requestRef, observation.observationRef(), claim);
	}

	/**
	 * Captures available selected-input and runner conditions before native execution.
	 *
	 * @param assignment retained current trial assignment
	 * @param request validated native request
	 * @param artifact selected packaged runner artifact
	 * @return complete known-or-limited material conditions
	 * @throws IOException if contract, candidate, or runner evidence is invalid
	 */
	MeasurementConditions conditions(TrialAssignment assignment, InitialTrialRunRequest request,
		Path artifact) throws IOException
	{
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(
			assignment.lineage().contractId());
		PromptSnapshot candidate = new PromptSnapshotRepository(store.directory()).load(
			assignment.candidateId());
		EvidenceRef candidateRef = store.appendImmutableJson("project-snapshot-manifests",
			JSON.writeValueAsBytes(candidate.manifest()));
		Map<MeasurementConditions.Kind, MeasurementConditions.Value> values =
			new EnumMap<>(MeasurementConditions.Kind.class);
		for (MeasurementConditions.Kind kind : MeasurementConditions.Kind.values())
			values.put(kind, new MeasurementConditions.Value.Unknown(
				"Condition has not been observed before native trial execution"));
		values.put(MeasurementConditions.Kind.CANDIDATE,
			new MeasurementConditions.Value.Known(candidateRef));
		values.put(MeasurementConditions.Kind.CONTRACT,
			new MeasurementConditions.Value.Known(new EvidenceRef("contracts", contract.contractId())));
		values.put(MeasurementConditions.Kind.GENERATOR,
			new MeasurementConditions.Value.Known(value(assignment.instance().generatorId())));
		values.put(MeasurementConditions.Kind.EVALUATOR,
			new MeasurementConditions.Value.Known(value(contract.evaluator())));
		values.put(MeasurementConditions.Kind.MODEL_PROFILE,
			new MeasurementConditions.Value.Known(value(request.modelProfile())));
		values.put(MeasurementConditions.Kind.HARNESS_RUNNER,
			new MeasurementConditions.Value.Known(value(RunnerRuntimeManifest.fingerprint(artifact))));
		values.put(MeasurementConditions.Kind.PROMPT_SOURCES,
			new MeasurementConditions.Value.Known(request.context().snapshot().snapshotManifestRef()));
		return new MeasurementConditions(values);
	}

	/**
	 * Publishes one selected or observed value as immutable comparison evidence.
	 *
	 * @param value serializable material condition
	 * @return immutable known-value reference
	 * @throws IOException if publication fails
	 */
	private EvidenceRef value(Object value) throws IOException
	{
		return store.appendImmutableJson("measurement-values", JSON.writeValueAsBytes(value));
	}
}
