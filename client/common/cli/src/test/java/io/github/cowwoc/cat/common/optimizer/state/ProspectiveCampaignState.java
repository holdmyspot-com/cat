package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test-owned durable states for mechanics that do not exercise contract confirmation.
 */
public final class ProspectiveCampaignState
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prevents construction.
	 */
	private ProspectiveCampaignState()
	{
	}

	/**
	 * Seeds a pristine unconfirmed test campaign without a production mutation route.
	 *
	 * @param store test store
	 * @param campaignId test campaign identity
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static void initialize(CampaignStore store, String campaignId) throws IOException
	{
		write(store, Campaign.create(campaignId, profile()));
	}

	/**
	 * Supplies an immutable model profile for prospective campaign fixtures.
	 *
	 * @return a valid test-only model profile
	 */
	public static ModelProfile profile()
	{
		return new ModelProfile("test-model", Optional.empty(), Map.of());
	}

	/**
	 * Registers a prospective test campaign under its correctly named workflow root.
	 *
	 * @param store test campaign store
	 * @return locator that owns subsequent inbox and lease construction
	 * @throws IOException if test registration fails
	 */
	public static CampaignLocator register(CampaignStore store) throws IOException
	{
		Campaign current = store.load();
		Path directory = store.directory();
		CampaignLocator locator = new CampaignLocator(directory.getParent());
		try
		{
			locator.requireRegisteredBinding(store.binding());
			return locator;
		}
		catch (IOException exception)
		{
			if (!exception.getMessage().contains("requires one exact registration"))
				throw exception;
		}
		CampaignLookupKey key = new CampaignLookupKey(directory.getParent(),
			Path.of("test-" + current.campaignId() + ".md"));
		CampaignHandle handle = new CampaignHandle(current.campaignId(), directory, current.status(),
			current.activeLineageId(), current.transitionCounter(),
			Files.getLastModifiedTime(directory.resolve("campaign.json")).toInstant(), current.closedPhase() != null);
		locator.register(key, handle);
		return locator;
	}

	/**
	 * Opens a locator-owned inbox for a prospective test campaign.
	 *
	 * @param store test campaign store
	 * @return stop inbox
	 * @throws IOException if registration fails
	 */
	public static CampaignStopInbox inbox(CampaignStore store) throws IOException
	{
		return register(store).openStopInbox(store.binding());
	}

	/**
	 * Opens a locator-owned lease service for a prospective test campaign.
	 *
	 * @param store test campaign store
	 * @return lease service
	 * @throws IOException if registration fails
	 */
	public static CampaignSchedulerLease lease(CampaignStore store) throws IOException
	{
		return register(store).openSchedulerLease(store.binding());
	}

	/**
	 * Claims through the locator-owned service for tests of later slot transitions.
	 *
	 * @param lease live scheduler owner
	 * @param logicalCallId exact logical call
	 * @param requestRef immutable C0R request
	 * @return retained slot
	 * @throws IOException if claim authority is absent
	 */
	public static CallSlot claim(SchedulerLease lease, String logicalCallId, EvidenceRef requestRef)
		throws IOException
	{
		CampaignBinding binding = lease.binding();
		CampaignSchedulerLease service = new CampaignLocator(binding.realDirectory().getParent()).
			openSchedulerLease(binding);
		return service.claimOrResumeCall(lease, logicalCallId, requestRef, Optional.empty(), Optional.empty()).slot();
	}

	/**
	 * Publishes a fixture producer attempt and C1's indexed pre-start record before starting a claimed call.
	 *
	 * @param store test campaign store
	 * @param lease live scheduler owner with an existing claimed call
	 * @param logicalCallId exact logical call
	 * @return the started slot
	 * @throws IOException if fixture evidence or C1 pre-start authority is invalid
	 */
	public static CallSlot prepareStart(CampaignStore store, SchedulerLease lease, String logicalCallId)
		throws IOException
	{
		EvidenceRef preStart = publishPreStart(store, lease, logicalCallId);
		return lease.prepareStart(logicalCallId, preStart);
	}

	/**
	 * Publishes a fixture producer attempt and C1's pre-start index while the slot remains claimed.
	 *
	 * @param store test campaign store
	 * @param lease live scheduler owner with an existing claimed call
	 * @param logicalCallId exact logical call
	 * @return the indexed pre-start record
	 * @throws IOException if fixture evidence or C1 pre-start authority is invalid
	 */
	public static EvidenceRef publishPreStart(CampaignStore store, SchedulerLease lease,
		String logicalCallId) throws IOException
	{
		OwnerAttempt ownerAttempt = publishOwnerAttempt(store, lease, logicalCallId);
		CampaignBinding binding = lease.binding();
		ClaimedSlot claimed = store.readCallSlotClaim(binding, logicalCallId);
		CallSlot slot = claimed.slot();
		CallSlotClaimRecord claim = claimed.claim();
		CallPreStartRecord record = new CallPreStartRecord(3, logicalCallId, slot.requestRef(),
			slot.slotClaimRef(), claim.preparationOwnerRequestRef(), claim.preparationCycleRef(),
			store.currentBeforeCallObservationRef(binding, logicalCallId), ProducerAttemptKind.EVALUATOR,
			ownerAttempt.attemptRef(), ownerAttempt.ownerRequestRef(),
			nativeRunId(logicalCallId), "a".repeat(64));
		EvidenceRef preStart = store.appendImmutableJson("call-pre-starts", JSON.writeValueAsBytes(record));
		store.publishCallPreStartIndex(binding, logicalCallId, preStart);
		return preStart;
	}

	/**
	 * Publishes a fixture owner request and indexed attempt for a claimed generic call.
	 *
	 * @param store the test campaign repository
	 * @param lease the live scheduler owner
	 * @param logicalCallId the claimed call identity
	 * @return the owner attempt and request references
	 * @throws IOException if fixture evidence cannot be retained
	 */
	public static OwnerAttempt publishOwnerAttempt(CampaignStore store, SchedulerLease lease,
		String logicalCallId) throws IOException
	{
		CampaignBinding binding = lease.binding();
		ClaimedSlot claimed = store.readCallSlotClaim(binding, logicalCallId);
		CallSlot slot = claimed.slot();
		CallSlotClaimRecord claim = claimed.claim();
		Campaign campaign = store.load();
		LineageSummary active = campaign.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow();
		LineageKey lineage = new LineageKey(active.lineageId(), active.confirmedContractId());
		EvidenceRef semanticRequest = store.appendImmutableJson("evaluator-requests",
			"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		PreparedLineageCall prepared = new PreparedLineageCall(1, ProducerAttemptKind.EVALUATOR,
			binding.campaignId(), lineage, logicalCallId, semanticRequest, slot.requestRef());
		EvidenceRef ownerRequest = store.appendImmutableJson("prepared-lineage-calls",
			JSON.writeValueAsBytes(prepared));
		ProducerAttemptHeader header = new ProducerAttemptHeader(ProducerAttemptKind.EVALUATOR,
			logicalCallId, slot.requestRef(), slot.slotClaimRef(), ownerRequest,
			claim.preparationCycleRef());
		EvidenceRef attempt = store.appendImmutableJson("evaluator-transport-attempts",
			JSON.writeValueAsBytes(Map.of("header", header, "requestRef", semanticRequest,
				"attemptOrdinal", 0)));
		String ownerIndexKey = OwnerAttemptIndexKey.deriveLineage(ProducerAttemptKind.EVALUATOR,
			ownerRequest, slot.requestRef(), slot.slotClaimRef(), 0);
		store.appendCreateOnceIndex("evaluator-execution-attempts", ownerIndexKey,
			new LineageEvidenceRef(lineage, attempt));
		return new OwnerAttempt(attempt, ownerRequest);
	}

	/**
	 * Test-owned references for one indexed producer attempt.
	 *
	 * @param attemptRef the indexed fixture attempt
	 * @param ownerRequestRef the prepared lineage owner request
	 */
	public record OwnerAttempt(EvidenceRef attemptRef, EvidenceRef ownerRequestRef)
	{
	}

	/**
	 * Gives fixture calls a stable runner-format identity across independent recovery checks.
	 *
	 * @param logicalCallId fixture logical call
	 * @return runner-format native run identity
	 */
	public static String nativeRunId(String logicalCallId)
	{
		return "run-" + Integer.toUnsignedString(logicalCallId.hashCode(), 36);
	}

	/**
	 * Seeds the next prospective confirmed lineage for tests of downstream mechanics.
	 *
	 * @param store test store
	 * @param contractId prospective contract identity
	 * @return prospective campaign
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static Campaign addLineage(CampaignStore store, String contractId) throws IOException
	{
		Campaign current = store.load();
		String lineageId = "lineage-" + (current.lineages().size() + 1) + "-" + contractId;
		List<LineageSummary> lineages = new ArrayList<>(current.lineages());
		lineages.add(new LineageSummary(lineageId, contractId, 1, List.of()));
		List<String> history = new ArrayList<>(current.history());
		history.add("prospective:" + lineageId);
		Campaign next = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), CampaignStatus.DEVELOPING,
			lineageId, null, null, null, current.transitionCounter() + 1, null, lineages, current.trials(), history,
			current.optimizationProfile(), current.transitionReceipts(), current.scheduler(),
			current.preparationScope(), current.activeFormalFailureTriggerRef(), current.measurementState(),
			current.terminalResults(), current.acknowledgedInterruptionStopRef());
		write(store, next);
		return next;
	}

	/**
	 * Seeds a phase for a test of a downstream campaign reader without invoking a lifecycle transition.
	 *
	 * @param store test campaign store whose state receives the phase
	 * @param status phase the downstream reader must observe
	 * @return prospective campaign containing the phase
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static Campaign withStatus(CampaignStore store, CampaignStatus status) throws IOException
	{
		Campaign current = store.load();
		CampaignStatus closedPhase = null;
		if (status == CampaignStatus.INTERRUPTED || status == CampaignStatus.CANCELLED)
			closedPhase = current.status();
		Campaign next = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), status,
			current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
			current.activeDevelopmentRoot(), current.transitionCounter() + 1, closedPhase, current.lineages(),
			current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
			current.scheduler(), current.preparationScope(), current.activeFormalFailureTriggerRef(),
			current.measurementState(), current.terminalResults(), current.acknowledgedInterruptionStopRef());
		write(store, next);
		return next;
	}

	/**
	 * Seeds a quiescent confirmed campaign suitable for a legacy revision-call control.
	 *
	 * @param store test store
	 * @param contractId prospective incumbent contract identity
	 * @return prospective quiescent campaign
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static Campaign confirmedForRevision(CampaignStore store, String contractId) throws IOException
	{
		Campaign current = addLineage(store, contractId);
		Campaign quiescent = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), CampaignStatus.CERTIFIED,
			current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
			current.activeDevelopmentRoot(), current.transitionCounter(), null, current.lineages(), current.trials(),
			current.history(), current.optimizationProfile(), current.transitionReceipts(), current.scheduler(),
			current.preparationScope(), current.activeFormalFailureTriggerRef(), current.measurementState(),
			current.terminalResults(), current.acknowledgedInterruptionStopRef());
		write(store, quiescent);
		return quiescent;
	}

	/**
	 * Seeds cancellation for a test that observes retained historical claim data.
	 *
	 * @param store test store
	 * @return prospective cancelled campaign
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static Campaign cancelled(CampaignStore store) throws IOException
	{
		Campaign current = store.load();
		Campaign cancelled = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), CampaignStatus.CANCELLED,
			current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
			current.activeDevelopmentRoot(), current.transitionCounter() + 1, current.status(), current.lineages(),
			current.trials(), current.history(), current.optimizationProfile(), current.transitionReceipts(),
			current.scheduler(), current.preparationScope(), current.activeFormalFailureTriggerRef(),
			current.measurementState(), current.terminalResults(), current.acknowledgedInterruptionStopRef());
		write(store, cancelled);
		return cancelled;
	}

	/**
	 * Seeds a typed development root for a persistence test without granting lifecycle authority.
	 *
	 * @param store test campaign store whose state receives the root
	 * @param root immutable development result to retain
	 * @return prospective campaign containing the root
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static Campaign developmentRoot(CampaignStore store, EvidenceRef root) throws IOException
	{
		Campaign current = store.load();
		Campaign next = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
			current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(), root,
			current.transitionCounter() + 1, current.closedPhase(), current.lineages(), current.trials(),
			current.history(), current.optimizationProfile(), current.transitionReceipts(), current.scheduler(),
			current.preparationScope(), current.activeFormalFailureTriggerRef(), current.measurementState(),
			current.terminalResults(), current.acknowledgedInterruptionStopRef());
		write(store, next);
		return next;
	}

	/**
	 * Seeds selected, applied, and reserved candidate state for a persistence-only test.
	 *
	 * @param store test campaign store
	 * @param selectedId prospective selected candidate, or null
	 * @param appliedId prospective applied candidate, or null
	 * @param allowances prospective active-lineage allowances
	 * @return prospective campaign written to the test store
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static Campaign withCandidateState(CampaignStore store, String selectedId, String appliedId,
		List<CandidateAllowance> allowances) throws IOException
	{
		Campaign current = store.load();
		List<LineageSummary> lineages = new ArrayList<>();
		for (LineageSummary lineage : current.lineages())
		{
			if (!lineage.lineageId().equals(current.activeLineageId()))
			{
				lineages.add(lineage);
				continue;
			}
			lineages.add(new LineageSummary(lineage.lineageId(), lineage.confirmedContractId(),
				allowances.size() + 1, allowances));
		}
		Campaign next = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
			current.activeLineageId(), selectedId, appliedId, current.activeDevelopmentRoot(),
			current.transitionCounter() + 1, current.closedPhase(), lineages, current.trials(), current.history(),
			current.optimizationProfile(), current.transitionReceipts(), current.scheduler(), current.preparationScope(),
			current.activeFormalFailureTriggerRef(), current.measurementState(), current.terminalResults(),
			current.acknowledgedInterruptionStopRef());
		write(store, next);
		return next;
	}

	/**
	 * Seeds logical trial records for a persistence-only test.
	 *
	 * @param store test campaign store
	 * @param trials prospective logical trials
	 * @return prospective campaign written to the test store
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static Campaign withTrials(CampaignStore store, List<TrialState> trials) throws IOException
	{
		Campaign current = store.load();
		Campaign next = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
			current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
			current.activeDevelopmentRoot(), current.transitionCounter() + 1, current.closedPhase(), current.lineages(),
			trials, current.history(), current.optimizationProfile(), current.transitionReceipts(), current.scheduler(),
			current.preparationScope(), current.activeFormalFailureTriggerRef(), current.measurementState(),
			current.terminalResults(), current.acknowledgedInterruptionStopRef());
		write(store, next);
		return next;
	}

	/**
	 * Replaces the active measurement reference for a cold-read integrity fixture.
	 *
	 * @param store test campaign store
	 * @param measurement replacement active measurement state
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static void withMeasurementState(CampaignStore store, MeasurementState measurement)
		throws IOException
	{
		Campaign current = store.load();
		Campaign next = new Campaign(Campaign.SCHEMA_VERSION, current.campaignId(), current.status(),
			current.activeLineageId(), current.currentCandidateId(), current.appliedCandidateId(),
			current.activeDevelopmentRoot(), current.transitionCounter(), current.closedPhase(),
			current.lineages(), current.trials(), current.history(), current.optimizationProfile(),
			current.transitionReceipts(), current.scheduler(), current.preparationScope(),
			current.activeFormalFailureTriggerRef(), measurement, current.terminalResults(),
			current.acknowledgedInterruptionStopRef());
		write(store, next);
	}

	/**
	 * Seeds a settled or adopted slot solely for downstream state-mechanics tests.
	 *
	 * @param store test store
	 * @param logicalCallId existing active slot
	 * @param completionRef prospective completion evidence
	 * @param adoptionRef prospective adoption evidence, when the slot should be adopted
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static void completedSlot(CampaignStore store, String logicalCallId, EvidenceRef completionRef,
		Optional<EvidenceRef> adoptionRef) throws IOException
	{
		Campaign current = store.load();
		List<CallSlot> slots = new ArrayList<>();
		boolean found = false;
		for (CallSlot slot : current.scheduler().slots())
		{
			if (!slot.logicalCallId().equals(logicalCallId))
			{
				slots.add(slot);
				continue;
			}
			if (slot.state() != CallSlot.State.ACTIVE && slot.state() != CallSlot.State.SETTLED)
				throw new IOException("Prospective completion requires an active or settled slot");
			CallSlot.State state = CallSlot.State.SETTLED;
			if (adoptionRef.isPresent())
				state = CallSlot.State.ADOPTED;
			slots.add(new CallSlot(logicalCallId, slot.fence(), state, slot.requestRef(), slot.slotClaimRef(),
				slot.preStartRef(),
				slot.runId(), slot.cancellationRequested(), Optional.of(completionRef), adoptionRef));
			found = true;
		}
		if (!found)
			throw new IOException("Prospective slot is missing: " + logicalCallId);
		SchedulerState scheduler = current.scheduler();
		write(store, current.withScheduler(new SchedulerState(scheduler.fence(), scheduler.invocations(),
			scheduler.stops(), slots, scheduler.barrier())));
	}

	/**
	 * Writes one caller-constructed prospective state into a test-owned campaign directory.
	 *
	 * @param store test store
	 * @param campaign prospective campaign
	 * @throws IOException if fixture bytes cannot be written
	 */
	public static void write(CampaignStore store, Campaign campaign) throws IOException
	{
		Files.createDirectories(store.directory());
		Files.write(store.directory().resolve("campaign.json"), JSON.writeValueAsBytes(campaign));
	}
}
