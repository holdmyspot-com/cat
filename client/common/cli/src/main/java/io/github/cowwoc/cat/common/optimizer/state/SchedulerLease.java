package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.ConfirmedSourceAuthorityService;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.runner.NativeRunCompletionEvidence;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.Arrays;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exclusive live owner of one campaign scheduling fence.
 */
public final class SchedulerLease implements AutoCloseable
{
	private static final Map<Path, Semaphore> LOCAL_OWNERS = new ConcurrentHashMap<>();

	private final CampaignBinding binding;
	private final CampaignStore store;
	private final long fence;
	private final String ownerId;
	private final String invocationId;
	private final FileChannel channel;
	private final FileLock lock;
	private final Semaphore localOwner;
	private final AtomicBoolean closed = new AtomicBoolean();
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Wraps a durable fence while retaining its live process-independent owner lock.
	 *
	 * @param store pinned campaign store
	 * @param binding exact campaign binding
	 * @param invocationId exact command invocation
	 * @throws IOException if locking or state binding fails
	 */
	SchedulerLease(CampaignStore store, CampaignBinding binding, String invocationId) throws IOException
	{
		Campaign initial = store.lockedSnapshot(binding);
		this.store = store;
		this.binding = binding;
		requireConfirmedSourceAuthority(initial);
		ownerId = UUID.randomUUID().toString();
		this.invocationId = invocationId;
		localOwner = LOCAL_OWNERS.computeIfAbsent(store.directory(), ignored -> new Semaphore(1, true));
		try
		{
			localOwner.acquire();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for campaign scheduling ownership", e);
		}
		try
		{
			channel = FileChannel.open(store.directory().resolve("campaign-owner.lock"),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		}
		catch (IOException | RuntimeException e)
		{
			localOwner.release();
			throw e;
		}
		try
		{
			lock = channel.lock();
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				channel.close();
			}
			finally
			{
				localOwner.release();
			}
			throw e;
		}
		try
		{
			fence = store.updateScheduler(binding, campaign -> bindInvocation(campaign, binding, invocationId)).fence();
		}
		catch (IOException | RuntimeException e)
		{
			close();
			throw e;
		}
	}

	/**
	 * Binds an open invocation and its pending stop to a new durable fence.
	 *
	 * @param campaign locked campaign snapshot
	 * @param binding exact campaign binding
	 * @param invocationId exact command invocation
	 * @return scheduler state with next fence
	 * @throws IOException if the invocation cannot obtain scheduling authority
	 */
	private static SchedulerState bindInvocation(Campaign campaign, CampaignBinding binding, String invocationId)
		throws IOException
	{
		if (campaign.status() == CampaignStatus.CANCELLED || campaign.status() == CampaignStatus.CERTIFIED ||
			campaign.status() == CampaignStatus.NOT_CERTIFIED_PLATEAU)
			throw new IOException("Cancelled or terminal campaign has no scheduling authority");
		InvocationStopRegistration registration = null;
		for (InvocationStopRegistration candidate : campaign.scheduler().invocations())
			if (candidate.invocationId().equals(invocationId) && candidate.binding().equals(binding) &&
				candidate.status() == InvocationStopRegistration.Status.OPEN)
				registration = candidate;
		if (registration == null)
			throw new IOException("Open invocation registration is missing: " + invocationId);
		boolean pendingStop = campaign.scheduler().stops().stream().anyMatch(stop ->
			stop.sourceInvocationId().equals(invocationId) && stop.status() == StopRequest.Status.PENDING);
		if (registration.hostObservationRef().isEmpty() && !pendingStop)
			throw new IOException("Invocation has no host observation or pending token stop: " + invocationId);
		long nextFence = Math.addExact(campaign.scheduler().fence(), 1);
		List<InvocationStopRegistration> invocations = new ArrayList<>(campaign.scheduler().invocations());
		for (int index = 0; index < invocations.size(); ++index)
		{
			InvocationStopRegistration current = invocations.get(index);
			if (current.invocationId().equals(invocationId))
			{
				invocations.set(index, new InvocationStopRegistration(current.registrationRef(), binding,
					invocationId, current.registeredCounter(), current.observedOwnerFence(),
					current.hostObservationRef(), current.status(), Optional.of(nextFence)));
				break;
			}
		}
		List<StopRequest> stops = new ArrayList<>();
		for (StopRequest stop : campaign.scheduler().stops())
		{
			if (stop.sourceInvocationId().equals(invocationId) && stop.sourceFence().isEmpty())
				stops.add(new StopRequest(stop.stopRef(), invocationId, Optional.of(nextFence), stop.reason(),
					stop.status(), stop.adoptingFence(), stop.logicalCallId(), stop.interruptionReceiptId()));
			else
				stops.add(stop);
		}
		Optional<SchedulerBarrier> barrier = campaign.scheduler().barrier();
		if (barrier.isPresent() && barrier.orElseThrow().proofRef().isEmpty())
		{
			SchedulerBarrier previous = barrier.orElseThrow();
			barrier = Optional.of(new SchedulerBarrier(previous.purpose(), previous.predecessorCounter(),
				nextFence, previous.lineage(), previous.phase(), Optional.empty()));
		}
		return new SchedulerState(nextFence, invocations, stops, campaign.scheduler().slots(), barrier);
	}

	/**
	 * Returns the exact campaign binding.
	 *
	 * @return campaign binding
	 */
	public CampaignBinding binding()
	{
		return binding;
	}

	/**
	 * Returns the durable owner fence.
	 *
	 * @return owner fence
	 */
	public long fence()
	{
		return fence;
	}

	/**
	 * Returns the distinct scheduler owner identity.
	 *
	 * @return owner identity
	 */
	public String ownerId()
	{
		return ownerId;
	}

	/**
	 * Returns the exact command invocation identity.
	 *
	 * @return invocation identity
	 */
	public String invocationId()
	{
		return invocationId;
	}

	/**
	 * Claims a unique logical call without starting native I/O.
	 *
	 * @param logicalCallId stable logical call identity
	 * @param requestRef immutable request evidence
	 * @param preparation validated inspection authority for an unconfirmed campaign
	 * @return durable claimed slot
	 * @throws IOException if this owner is stale, stopped, or the call is already claimed
	 */
	ClaimedSlot claimOrResumeCall(String logicalCallId, EvidenceRef requestRef,
		Optional<PreparedInspectionCall> preparation) throws IOException
	{
		if (logicalCallId == null || logicalCallId.isBlank() || requestRef == null || preparation == null)
			throw new IllegalArgumentException("Call identity and request evidence are required");
		Optional<EvidenceRef> preparationOwnerRequestRef = preparation.map(PreparedInspectionCall::ownerRef);
		Optional<EvidenceRef> preparationCycleRef = preparation.map(value ->
			value.owner().preparationCycleRef());
		if (!requestRef.collection().equals("run-requests"))
			throw new IOException("Call claim requires C0R run request evidence");
		requireLive();
		Campaign snapshot = store.lockedSnapshot(binding);
		requireConfirmedSourceAuthority(snapshot);
		requireCandidateRequest(snapshot, logicalCallId, requestRef);
		byte[] requestBytes = store.readImmutableJson(requestRef);
		try
		{
			Map<?, ?> request = JSON.readValue(requestBytes, Map.class);
			if (request == null || !logicalCallId.equals(request.get("logicalCallId")))
				throw new IOException("C0R run request identifies another logical call");
		}
		catch (RuntimeException exception)
		{
			throw new IOException("C0R run request is malformed", exception);
		}
		Optional<EvidenceRef> trialAssignmentRef = validateTrialRequest(logicalCallId, requestRef);
		SchedulerState scheduler = store.updateSchedulerWithEvidence(binding, (campaign, evidence) ->
		{
			store.readImmutableJson(requestRef);
			requireCandidateRequest(campaign, logicalCallId, requestRef);
			requireTrialAssignment(campaign, logicalCallId, trialAssignmentRef);
			List<CallSlot> slots = new ArrayList<>(campaign.scheduler().slots());
			for (int index = 0; index < slots.size(); ++index)
			{
				CallSlot existing = slots.get(index);
				if (!existing.logicalCallId().equals(logicalCallId))
					continue;
				requireCurrent(campaign, true, false);
				requireCallPhase(campaign);
				requirePreparationClaim(campaign, preparation);
				requireBeforeCallObservation(campaign, logicalCallId, preparationOwnerRequestRef,
					preparationCycleRef, requestRef);
				ClaimedSlot retained = store.verifySlotClaim(binding, campaign, existing);
				CallSlotClaimRecord claim = retained.claim();
				if (!claim.runRequestRef().equals(requestRef) ||
					!claim.preparationOwnerRequestRef().equals(preparationOwnerRequestRef) ||
					!claim.preparationCycleRef().equals(preparationCycleRef) || existing.fence() > fence)
					throw new IOException("Conflicting logical call claim: " + logicalCallId);
				if (existing.fence() == fence)
				{
					if (!claim.invocationId().equals(invocationId))
						throw new IOException("Claim belongs to another invocation");
					return campaign.scheduler();
				}
				if (existing.state() != CallSlot.State.CLAIMED || existing.preStartRef().isPresent() ||
					existing.runId().isPresent() || existing.completionRef().isPresent() ||
					existing.adoptionRef().isPresent() || existing.cancellationRequested())
					throw new IOException("Only an unstarted CLAIMED slot can transfer its fence");
				slots.set(index, new CallSlot(logicalCallId, fence, existing.state(), existing.requestRef(),
					existing.slotClaimRef(), existing.preStartRef(), existing.runId(),
					existing.cancellationRequested(), existing.completionRef(), existing.adoptionRef()));
				return new SchedulerState(fence, campaign.scheduler().invocations(), campaign.scheduler().stops(),
					slots, campaign.scheduler().barrier());
			}
			requireCurrent(campaign, true);
			requireCallPhase(campaign);
			requirePreparationClaim(campaign, preparation);
			requireBeforeCallObservation(campaign, logicalCallId, preparationOwnerRequestRef,
				preparationCycleRef, requestRef);
			CallSlotClaimRecord claim = new CallSlotClaimRecord(1, binding.campaignId(), invocationId,
				logicalCallId, fence, requestRef, preparationOwnerRequestRef, preparationCycleRef);
			EvidenceRef claimRef = evidence.appendImmutableJson("call-slot-claims", JSON.writeValueAsBytes(claim));
			slots.add(new CallSlot(logicalCallId, fence, CallSlot.State.CLAIMED, requestRef, claimRef,
				Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty()));
			return new SchedulerState(fence, campaign.scheduler().invocations(), campaign.scheduler().stops(), slots,
				campaign.scheduler().barrier());
		});
		CallSlot committed = scheduler.slots().stream().filter(slot -> slot.logicalCallId().equals(logicalCallId)).
			findFirst().orElseThrow();
		return store.verifySlotClaim(binding, store.lockedSnapshot(binding), committed);
	}

	/**
	 * Validates that a selected trial uses the exact request produced from its assignment.
	 *
	 * @param logicalCallId requested call identity
	 * @param requestRef caller-supplied request evidence
	 * @return current trial assignment, if the call identifies a committed trial
	 * @throws IOException if the request differs from the assigned trial
	 */
	private Optional<EvidenceRef> validateTrialRequest(String logicalCallId, EvidenceRef requestRef)
		throws IOException
	{
		Campaign campaign = store.lockedSnapshot(binding);
		Optional<TrialState> trial = campaign.trials().stream().filter(value ->
			value.logicalTrialId().equals(logicalCallId)).findFirst();
		if (trial.isEmpty())
			return Optional.empty();
		EvidenceRef assignmentRef = trial.orElseThrow().selectionEvidence();
		CampaignLocator locator = new CampaignLocator(store.directory().getParent());
		EvidenceRef expectedRequest = new InitialTrialRequestService(locator, store).prepare(assignmentRef);
		if (!requestRef.equals(expectedRequest))
			throw new IOException("Trial request does not match its committed assignment: " + logicalCallId);
		return Optional.of(assignmentRef);
	}

	/**
	 * Requires an indexed candidate call to use its source-backed native request and pristine private worktree.
	 *
	 * @param campaign validated campaign at the claim or pre-start boundary
	 * @param logicalCallId call seeking authorization
	 * @param requestRef caller-supplied native request
	 * @throws IOException if a published generation intent lacks its exact request or private authority
	 */
	private void requireCandidateRequest(Campaign campaign, String logicalCallId, EvidenceRef requestRef)
		throws IOException
	{
		if (campaign.activeLineageId() == null)
			return;
		LineageSummary active = campaign.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow(() ->
			new IOException("Active candidate-generation lineage is missing"));
		LineageKey lineage = new LineageKey(active.lineageId(), active.confirmedContractId());
		Optional<LineageEvidenceRef> intent = store.findCreateOnceIndex("generation-attempt-intent-index",
			logicalCallId, lineage, campaign);
		if (intent.isEmpty())
		{
			if (store.findRetainedGenerationIntent(logicalCallId).isPresent())
				throw new IOException("Candidate generation intent index is missing: " + logicalCallId);
			return;
		}
		EvidenceRef intentRef = intent.orElseThrow().evidence();
		EvidenceRef readinessRef = store.readCreateOnceIndexForCampaign("generation-attempt-readiness-index",
			intentRef.evidenceId(), lineage, campaign).evidence();
		EvidenceRef semanticRef = store.readCreateOnceIndexForCampaign("candidate-generation-request-index",
			readinessRef.evidenceId(), lineage, campaign).evidence();
		CampaignLocator locator = new CampaignLocator(store.directory().getParent());
		CandidateGenerationRunRequest request = new CandidateGenerationRunRequestService(locator, store).
			read(requestRef);
		if (!request.logicalCallId().equals(logicalCallId) ||
			!request.semanticRequestRef().equals(semanticRef) ||
			!request.context().readinessRef().equals(readinessRef))
			throw new IOException("Candidate generation native request differs from its indexed intent");
		new GenerationAttemptWorktreeService(locator, store).requirePristine(readinessRef);
	}

	/**
	 * Rechecks assigned trial authority inside the scheduler mutation.
	 *
	 * @param campaign current campaign state
	 * @param logicalCallId call seeking a slot
	 * @param assignmentRef assignment verified before entering the mutation
	 * @throws IOException if the assignment or active measurement changed before the claim
	 */
	private void requireTrialAssignment(Campaign campaign, String logicalCallId,
		Optional<EvidenceRef> assignmentRef) throws IOException
	{
		Optional<TrialState> trial = campaign.trials().stream().filter(value ->
			value.logicalTrialId().equals(logicalCallId)).findFirst();
		if (trial.isEmpty() && assignmentRef.isEmpty())
			return;
		if (trial.isEmpty() || assignmentRef.isEmpty())
			throw new IOException("Trial assignment changed before call claim: " + logicalCallId);
		TrialState selected = trial.orElseThrow();
		EvidenceRef selectedAssignmentRef = assignmentRef.orElseThrow();
		TrialAssignment assignment = store.readTrialAssignment(selectedAssignmentRef);
		if (campaign.status() != CampaignStatus.DEVELOPING ||
			campaign.measurementState() == null || campaign.measurementState().pause().isPresent() ||
			!selected.selectionEvidence().equals(selectedAssignmentRef) ||
			selected.status() != TrialStatus.ASSIGNED ||
			!assignment.measurementEpochRef().equals(campaign.measurementState().epochRef()) ||
			!assignment.lineage().lineageId().equals(campaign.activeLineageId()) ||
			!assignment.candidateId().equals(campaign.currentCandidateId()))
			throw new IOException("Trial assignment is no longer current for call claim: " + logicalCallId);
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(
			assignment.lineage().contractId());
		DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(store).
			readCurrent(campaign);
		DevelopmentWeaknessGuard.requireNoClearlyWeakCategory(evidence, contract);
		new DevelopmentReviewService(store).requireEligible(evidence, contract);
	}

	/**
	 * Rechecks preparation authority inside the locked scheduler mutation.
	 *
	 * @param campaign current campaign state
	 * @param preparation validated inspection owner, when the campaign is unconfirmed
	 * @throws IOException if the claim no longer belongs to the current phase or scope
	 */
	private void requirePreparationClaim(Campaign campaign,
		Optional<PreparedInspectionCall> preparation) throws IOException
	{
		if (campaign.activeLineageId() != null)
		{
			if (preparation.isPresent())
				throw new IOException("Confirmed lineage call cannot use preparation authority");
			return;
		}
		if (preparation.isEmpty())
			throw new IOException("Unconfirmed preparation call requires an indexed inspection owner");
		PreparedInspectionCall call = preparation.orElseThrow();
		PreparationAuthority authority = call.cycle().authority();
		if (campaign.status() != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION ||
			authority.kind() != PreparationAuthority.Kind.INITIAL ||
			!authority.campaignId().equals(campaign.campaignId()) ||
			authority.transitionCounter() != campaign.transitionCounter() ||
			authority.preparationGeneration() != campaign.preparationScope().generation() ||
			!authority.sourceChangeRef().equals(campaign.preparationScope().sourceChangeRef()) ||
			campaign.scheduler().barrier().isPresent())
			throw new IOException("Inspection owner no longer belongs to current preparation authority");
	}

	/**
	 * Commits the durable pre-start boundary before native start.
	 *
	 * @param logicalCallId claimed call identity
	 * @param preStartRef immutable pre-start evidence
	 * @return starting slot
	 * @throws IOException if the claim, evidence, fence, or stop state is invalid
	 */
	public CallSlot prepareStart(String logicalCallId, EvidenceRef preStartRef) throws IOException
	{
		if (preStartRef == null)
			throw new NullPointerException("preStartRef");
		requireConfirmedSourceAuthority(store.lockedSnapshot(binding));
		return updateSlot(logicalCallId, true, true, (campaign, slot) ->
		{
			Optional<EvidenceRef> assignmentRef = campaign.trials().stream().filter(trial ->
				trial.logicalTrialId().equals(logicalCallId)).map(TrialState::selectionEvidence).findFirst();
			requireTrialAssignment(campaign, logicalCallId, assignmentRef);
			CallSlotClaimRecord claim = store.verifySlotClaim(binding, campaign, slot).claim();
			requireCandidateRequest(campaign, logicalCallId, claim.runRequestRef());
			requireBeforeCallObservation(campaign, logicalCallId, claim.preparationOwnerRequestRef(),
				claim.preparationCycleRef(), claim.runRequestRef());
			if (slot.state() != CallSlot.State.CLAIMED || slot.fence() != fence)
				throw new IOException("Call is not claimed by this owner: " + logicalCallId);
			EvidenceRef indexed = store.readCallPreStartIndexLocked(binding, campaign, slot).
				orElseThrow(() -> new IOException("Call pre-start index is missing: " + logicalCallId));
			if (!indexed.equals(preStartRef))
				throw new IOException("Call pre-start index identifies another record: " + logicalCallId);
			return new CallSlot(logicalCallId, fence, CallSlot.State.STARTING, slot.requestRef(),
				slot.slotClaimRef(),
				Optional.of(preStartRef), Optional.empty(), slot.cancellationRequested(), Optional.empty(),
				Optional.empty());
		});
	}

	/**
	 * Records a native run identity after start succeeds.
	 *
	 * @param logicalCallId starting call identity
	 * @param runId native run identity
	 * @return active slot
	 * @throws IOException if the pre-start boundary or fence is invalid
	 */
	public CallSlot activate(String logicalCallId, String runId) throws IOException
	{
		if (runId == null || runId.isBlank())
			throw new IllegalArgumentException("Native run id must be non-blank");
		return updateSlot(logicalCallId, true, false, (campaign, slot) ->
		{
			if (slot.state() != CallSlot.State.STARTING || slot.fence() != fence)
				throw new IOException("Call has no starting boundary owned by this fence: " + logicalCallId);
			if (slot.cancellationRequested())
				throw new IOException("Cancelled starting call cannot activate: " + logicalCallId);
			CallPreStartRecord preStart = store.verifyCallPreStart(binding, campaign, slot,
				slot.preStartRef().orElseThrow());
			if (!preStart.runId().equals(runId))
				throw new IOException("Native run ID differs from the retained pre-start record: " + logicalCallId);
			return new CallSlot(logicalCallId, fence, CallSlot.State.ACTIVE, slot.requestRef(),
				slot.slotClaimRef(),
				slot.preStartRef(), Optional.of(runId), slot.cancellationRequested(), Optional.empty(),
				Optional.empty());
		});
	}

	/**
	 * Retains a native completion or proven start failure before its lifecycle adoption.
	 *
	 * @param logicalCallId started call identity
	 * @param completionRef immutable completion evidence
	 * @return settled slot
	 * @throws IOException if the slot or evidence is invalid
	 */
	public CallSlot settle(String logicalCallId, EvidenceRef completionRef) throws IOException
	{
		if (completionRef == null)
			throw new NullPointerException("completionRef");
		return updateSlot(logicalCallId, false, false, (campaign, slot) ->
		{
			if (slot.state() == CallSlot.State.SETTLED && slot.completionRef().equals(Optional.of(completionRef)))
			{
				verifyIndexedCompletion(slot, completionRef);
				return slot;
			}
			if (slot.state() != CallSlot.State.STARTING && slot.state() != CallSlot.State.ACTIVE)
				throw new IOException("Call is not started: " + logicalCallId);
			NativeRunCompletionEvidence completion = verifyIndexedCompletion(slot, completionRef);
			Optional<String> settledRunId = slot.runId();
			if (settledRunId.isEmpty())
				settledRunId = completion.runId();
			return new CallSlot(logicalCallId, slot.fence(), CallSlot.State.SETTLED, slot.requestRef(),
				slot.slotClaimRef(), slot.preStartRef(), settledRunId, slot.cancellationRequested(),
				Optional.of(completionRef), Optional.empty());
		});
	}

	/**
	 * Verifies exact indexed completion while the scheduler state lock is held.
	 *
	 * @param slot retained slot
	 * @param completionRef typed completion evidence
	 * @return validated completion retaining the observed native run identity
	 * @throws IOException if the index or evidence conflicts
	 */
	private NativeRunCompletionEvidence verifyIndexedCompletion(CallSlot slot, EvidenceRef completionRef)
		throws IOException
	{
		Optional<EvidenceRef> indexed = store.readNativeCompletionIndexLocked(binding, slot);
		if (!indexed.equals(Optional.of(completionRef)))
			throw new IOException("Native completion is not indexed for its retained slot claim");
		return store.verifyNativeCompletion(binding, slot, completionRef);
	}

	/**
	 * Marks a retained completion adopted without starting another native call.
	 *
	 * @param logicalCallId settled call identity
	 * @param adoptionRef complete producer record evidence
	 * @return adopted slot
	 * @throws IOException if the slot or adoption evidence is invalid
	 */
	public CallSlot adopt(String logicalCallId, EvidenceRef adoptionRef) throws IOException
	{
		if (adoptionRef == null)
			throw new NullPointerException("adoptionRef");
		requireLive();
		SchedulerState scheduler = store.updateSchedulerWithEvidence(binding, (campaign, evidence) ->
		{
			requireCurrent(campaign, false);
			List<CallSlot> slots = new ArrayList<>(campaign.scheduler().slots());
			for (int index = 0; index < slots.size(); ++index)
			{
				CallSlot slot = slots.get(index);
				if (!slot.logicalCallId().equals(logicalCallId))
					continue;
				store.verifySlotClaim(binding, campaign, slot);
				if (slot.state() != CallSlot.State.SETTLED && slot.state() != CallSlot.State.ADOPTED)
					throw new IOException("Call is not settled: " + logicalCallId);
				EvidenceRef completionRef = slot.completionRef().orElseThrow();
				verifyIndexedCompletion(slot, completionRef);
				store.readImmutableJson(adoptionRef);
				CallAdoptionRecord envelope = new CallAdoptionRecord(1, binding.campaignId(), logicalCallId,
					slot.requestRef(), slot.slotClaimRef(), completionRef, adoptionRef);
				byte[] envelopeBytes = JSON.writeValueAsBytes(envelope);
				if (slot.state() == CallSlot.State.ADOPTED)
				{
					EvidenceRef retained = slot.adoptionRef().orElseThrow();
					if (!retained.collection().equals("call-adoptions") ||
						!Arrays.equals(store.readImmutableJson(retained), envelopeBytes))
						throw new IOException("Conflicting adopted producer record");
					return campaign.scheduler();
				}
				EvidenceRef envelopeRef = evidence.appendImmutableJson("call-adoptions", envelopeBytes);
				slots.set(index, new CallSlot(logicalCallId, slot.fence(), CallSlot.State.ADOPTED,
					slot.requestRef(), slot.slotClaimRef(), slot.preStartRef(), slot.runId(),
					slot.cancellationRequested(), slot.completionRef(), Optional.of(envelopeRef)));
				return new SchedulerState(fence, campaign.scheduler().invocations(), campaign.scheduler().stops(),
					slots, campaign.scheduler().barrier());
			}
			throw new IOException("Logical call slot is missing: " + logicalCallId);
		});
		return scheduler.slots().stream().filter(slot -> slot.logicalCallId().equals(logicalCallId)).
			findFirst().orElseThrow();
	}

	/**
	 * Sets the durable cancellation flag before requesting native interruption.
	 *
	 * @param logicalCallId starting or active call identity
	 * @return flagged slot
	 * @throws IOException if the exact starting or active call cannot be reconciled
	 */
	public CallSlot requestCancellation(String logicalCallId) throws IOException
	{
		return updateSlot(logicalCallId, false, false, (campaign, slot) ->
		{
			if (slot.state() != CallSlot.State.STARTING && slot.state() != CallSlot.State.ACTIVE)
				throw new IOException("Call is not starting or active: " + logicalCallId);
			if (slot.cancellationRequested())
				return slot;
			return new CallSlot(logicalCallId, slot.fence(), slot.state(), slot.requestRef(),
				slot.slotClaimRef(),
				slot.preStartRef(), slot.runId(), true, slot.completionRef(), slot.adoptionRef());
		});
	}

	/**
	 * Persists a purpose-bound barrier before any further ordinary claim or start.
	 *
	 * @param purpose lifecycle operation requiring quiescence
	 * @param expectedCounter exact predecessor campaign counter
	 * @return durable barrier
	 * @throws IOException if the owner, phase, counter, or existing barrier differs
	 */
	public SchedulerBarrier beginQuiescence(QuiescencePurpose purpose, long expectedCounter) throws IOException
	{
		if (purpose == null)
			throw new NullPointerException("purpose");
		requireLive();
		SchedulerState scheduler = store.updateScheduler(binding, campaign ->
		{
			requireCurrent(campaign, false);
			if (campaign.transitionCounter() != expectedCounter || campaign.activeLineageId() == null)
				throw new IOException("Quiescence requires the exact confirmed lineage and counter");
			SchedulerBarrier barrier = new SchedulerBarrier(purpose, expectedCounter, fence,
				campaign.activeLineageId(), campaign.status(), Optional.empty());
			if (campaign.scheduler().barrier().isPresent())
			{
				if (!campaign.scheduler().barrier().orElseThrow().equals(barrier))
					throw new IOException("A different quiescence barrier already exists");
				return campaign.scheduler();
			}
			return new SchedulerState(fence, campaign.scheduler().invocations(), campaign.scheduler().stops(),
				campaign.scheduler().slots(), Optional.of(barrier));
		});
		return scheduler.barrier().orElseThrow();
	}

	/**
	 * Persists and verifies the rooted adopted-slot proof for the current barrier.
	 *
	 * @return immutable proof reference
	 * @throws IOException if any slot, stop, lineage, phase, counter, or fence is not quiescent
	 */
	public EvidenceRef proveQuiescent() throws IOException
	{
		requireLive();
		Campaign snapshot = store.lockedSnapshot(binding);
		if (snapshot.scheduler().fence() != fence)
			throw new IOException("Quiescence owner fence is stale");
		SchedulerBarrier barrier = snapshot.scheduler().barrier().orElseThrow(() ->
			new IOException("Quiescence barrier is missing"));
		if (barrier.proofRef().isPresent())
		{
			EvidenceRef existing = barrier.proofRef().orElseThrow();
			validateProof(existing, barrier);
			return existing;
		}
		SchedulerBarrier activeBarrier = requireQuiescent(snapshot);
		byte[] slots = JSON.writeValueAsBytes(snapshot.scheduler().slots());
		EvidenceRef root = store.appendImmutableJson("scheduler-call-slot-roots", slots);
		if (!Arrays.equals(store.readImmutableJson(root), slots))
			throw new IOException("Call-slot root did not round-trip");
		SchedulerQuiescenceProof proof = new SchedulerQuiescenceProof(1, binding, activeBarrier.purpose(),
			activeBarrier.predecessorCounter(), activeBarrier.schedulerFence(), activeBarrier.lineage(),
			activeBarrier.phase(), root,
			Instant.now());
		EvidenceRef proofRef = store.appendImmutableJson("scheduler-quiescence-proofs",
			JSON.writeValueAsBytes(proof));
		byte[] stored = store.readImmutableJson(proofRef);
		SchedulerQuiescenceProof reloaded = JSON.readValue(stored, SchedulerQuiescenceProof.class);
		if (!proof.equals(reloaded) || !Arrays.equals(stored, JSON.writeValueAsBytes(reloaded)))
			throw new IOException("Quiescence proof failed strict reload");
		validateProof(proofRef, activeBarrier);
		SchedulerState committed = store.updateScheduler(binding, campaign ->
		{
			SchedulerBarrier current = requireQuiescent(campaign);
			if (!current.equals(activeBarrier) ||
				!campaign.scheduler().slots().equals(snapshot.scheduler().slots()))
				throw new IOException("Quiescence state changed before proof commit");
			SchedulerBarrier proven = new SchedulerBarrier(current.purpose(), current.predecessorCounter(),
				current.schedulerFence(), current.lineage(), current.phase(), Optional.of(proofRef));
			return new SchedulerState(fence, campaign.scheduler().invocations(), campaign.scheduler().stops(),
				campaign.scheduler().slots(), Optional.of(proven));
		});
		return committed.barrier().orElseThrow().proofRef().orElseThrow();
	}

	/**
	 * Rehashes an existing proof and its rooted adopted-slot snapshot against the durable barrier.
	 *
	 * @param proofRef immutable proof reference
	 * @param barrier durable purpose-bound barrier
	 * @throws IOException if the artifact, identity, or rooted slots are invalid
	 */
	private void validateProof(EvidenceRef proofRef, SchedulerBarrier barrier) throws IOException
	{
		if (!proofRef.collection().equals("scheduler-quiescence-proofs"))
			throw new IOException("Quiescence proof has the wrong collection");
		try
		{
			byte[] bytes = store.readImmutableJson(proofRef);
			SchedulerQuiescenceProof proof = JSON.readValue(bytes, SchedulerQuiescenceProof.class);
			if (proof == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(proof)) ||
				!proof.binding().equals(binding) || proof.purpose() != barrier.purpose() ||
				proof.predecessorCounter() != barrier.predecessorCounter() ||
				proof.schedulerFence() != barrier.schedulerFence() ||
				!proof.lineage().equals(barrier.lineage()) || proof.phase() != barrier.phase() ||
				!proof.callSlotRootRef().collection().equals("scheduler-call-slot-roots"))
				throw new IOException("Quiescence proof conflicts with its barrier");
			byte[] rootBytes = store.readImmutableJson(proof.callSlotRootRef());
			CallSlot[] slots = JSON.readValue(rootBytes, CallSlot[].class);
			if (slots == null || !Arrays.equals(rootBytes, JSON.writeValueAsBytes(slots)) ||
				Arrays.stream(slots).anyMatch(slot -> slot.state() != CallSlot.State.ADOPTED) ||
				Arrays.stream(slots).map(CallSlot::logicalCallId).distinct().count() != slots.length)
				throw new IOException("Quiescence proof has an incomplete call-slot root");
		}
		catch (RuntimeException e)
		{
			throw new IOException("Quiescence proof is malformed", e);
		}
	}

	/**
	 * Checks the exact barrier and all retained call slots.
	 *
	 * @param campaign current campaign state
	 * @return matching barrier
	 * @throws IOException if the campaign is not yet quiescent
	 */
	private SchedulerBarrier requireQuiescent(Campaign campaign) throws IOException
	{
		requireCurrent(campaign, false);
		SchedulerBarrier barrier = campaign.scheduler().barrier().orElseThrow(() ->
			new IOException("Quiescence barrier is missing"));
		if (barrier.schedulerFence() != fence || barrier.predecessorCounter() != campaign.transitionCounter() ||
			!barrier.lineage().equals(campaign.activeLineageId()) || barrier.phase() != campaign.status() ||
			campaign.scheduler().hasUnadoptedCalls() ||
			campaign.scheduler().stops().stream().anyMatch(stop -> stop.status() == StopRequest.Status.PENDING))
			throw new IOException("Campaign has pending work or a stale quiescence barrier");
		return barrier;
	}

	/**
	 * Validates the live owner and updates exactly one slot under the state lock.
	 *
	 * @param logicalCallId exact call identity
	 * @param blockOnStop whether a pending stop prohibits this mutation
	 * @param requireActivePhase whether the campaign must be able to start a model call
	 * @param mutation slot transition
	 * @return committed slot
	 * @throws IOException if the owner or slot transition is invalid
	 */
	private CallSlot updateSlot(String logicalCallId, boolean blockOnStop, boolean requireActivePhase,
		SlotMutation mutation) throws IOException
	{
		if (logicalCallId == null || logicalCallId.isBlank())
			throw new IllegalArgumentException("Logical call id must be non-blank");
		requireLive();
		SchedulerState scheduler = store.updateScheduler(binding, campaign ->
		{
			requireCurrent(campaign, blockOnStop);
			if (requireActivePhase)
				requireCallPhase(campaign);
			List<CallSlot> slots = new ArrayList<>(campaign.scheduler().slots());
			for (int index = 0; index < slots.size(); ++index)
			{
				CallSlot slot = slots.get(index);
				if (slot.logicalCallId().equals(logicalCallId))
				{
					store.verifySlotClaim(binding, campaign, slot);
					CallSlot replacement = mutation.apply(campaign, slot);
					if (!replacement.slotClaimRef().equals(slot.slotClaimRef()))
						throw new IOException("Call-slot claim is immutable");
					slots.set(index, replacement);
					return new SchedulerState(fence, campaign.scheduler().invocations(),
						campaign.scheduler().stops(), slots, campaign.scheduler().barrier());
				}
			}
			throw new IOException("Logical call slot is missing: " + logicalCallId);
		});
		return scheduler.slots().stream().filter(slot -> slot.logicalCallId().equals(logicalCallId)).
			findFirst().orElseThrow();
	}

	/**
	 * Refuses new native work in terminal or interrupted phases.
	 *
	 * @param campaign current campaign state
	 * @throws IOException if the campaign phase cannot start a model call
	 */
	private void requireCallPhase(Campaign campaign) throws IOException
	{
		if (campaign.status() != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION &&
			campaign.status() != CampaignStatus.DEVELOPING && campaign.status() != CampaignStatus.FORMAL_TESTING)
			throw new IOException("Campaign phase cannot start a model call: " + campaign.status());
		if (campaign.measurementState() != null && campaign.measurementState().pause().isPresent())
			throw new IOException("Campaign is paused for a material measurement change");
	}

	/**
	 * Requires the current indexed observation for the claimed call.
	 *
	 * @param campaign current campaign state
	 * @param logicalCallId exact call seeking admission
	 * @param ownerRef indexed preparation owner for an unconfirmed inspection
	 * @param cycleRef indexed preparation cycle for an unconfirmed inspection
	 * @param requestRef retained native run request
	 * @throws IOException if the observation is missing, stale, or foreign
	 */
	private void requireBeforeCallObservation(Campaign campaign, String logicalCallId,
		Optional<EvidenceRef> ownerRef, Optional<EvidenceRef> cycleRef, EvidenceRef requestRef)
		throws IOException
	{
		if (campaign.status() == CampaignStatus.AWAITING_CONTRACT_CONFIRMATION)
		{
			if (ownerRef.isEmpty() || cycleRef.isEmpty())
				throw new IOException("Inspection measurement observation requires preparation authority");
			store.verifyPreparationBeforeCallObservation(binding, campaign, ownerRef.orElseThrow(),
				cycleRef.orElseThrow(), logicalCallId, requestRef);
			return;
		}
		if (hasConfirmedReceipt(campaign))
			store.verifyBeforeCallObservation(binding, campaign, logicalCallId);
	}

	/**
	 * Rechecks the indexed source authority before a genuinely confirmed model call can be owned or started.
	 *
	 * @param campaign current campaign outside a campaign-store mutation lock
	 * @throws IOException if the current claim lacks its immutable source authority
	 */
	private void requireConfirmedSourceAuthority(Campaign campaign) throws IOException
	{
		if (!hasConfirmedReceipt(campaign))
			return;
		LineageSummary active = campaign.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow(() ->
			new IOException("Active confirmed lineage is missing"));
		CampaignLocator locator = new CampaignLocator(store.directory().getParent());
		new ConfirmedSourceAuthorityService(locator, store).load(active.confirmedContractId());
	}

	/**
	 * Distinguishes a real confirmed lineage from a prospective fixture without a producer receipt.
	 *
	 * @param campaign current campaign
	 * @return whether its active lineage has a committed confirmation receipt
	 */
	private static boolean hasConfirmedReceipt(Campaign campaign)
	{
		return campaign.activeLineageId() != null && campaign.transitionReceipts().stream().anyMatch(receipt ->
			(receipt.kind() == TransitionKind.CONFIRMED_CONTRACT ||
				receipt.kind() == TransitionKind.REVISED_CONTRACT) &&
				receipt.result().lineageId().equals(campaign.activeLineageId()));
	}

	/**
	 * Rejects displaced owners and blocks new work after a stop.
	 *
	 * @param campaign current locked campaign
	 * @param blockOnStop whether a pending stop prohibits this mutation
	 * @throws IOException if the owner is displaced or stopped
	 */
	private void requireCurrent(Campaign campaign, boolean blockOnStop) throws IOException
	{
		requireCurrent(campaign, blockOnStop, true);
	}

	/**
	 * Checks authority while permitting only an equal prior slot to advance its mutable owner fence.
	 *
	 * @param campaign locked campaign
	 * @param blockOnStop whether pending stop and barrier prohibit the operation
	 * @param blockPredecessor whether a predecessor's unfinished slot prohibits the operation
	 * @throws IOException if scheduling authority is absent
	 */
	private void requireCurrent(Campaign campaign, boolean blockOnStop, boolean blockPredecessor)
		throws IOException
	{
		if (campaign.scheduler().fence() != fence)
			throw new IOException("Scheduler owner fence is stale: " + fence);
		if (blockOnStop && campaign.scheduler().stops().stream().anyMatch(stop ->
			blocksCurrentClaim(campaign, stop)))
			throw new IOException("Pending stop blocks a new call boundary");
		if (blockOnStop && campaign.scheduler().invocations().stream().noneMatch(registration ->
			registration.invocationId().equals(invocationId) &&
			registration.status() == InvocationStopRegistration.Status.OPEN &&
			registration.hostObservationRef().isPresent() &&
			registration.boundFence().equals(Optional.of(fence))))
			throw new IOException("Model call requires an observed OPEN invocation bound to this fence");
		if (blockOnStop && campaign.scheduler().barrier().isPresent())
			throw new IOException("Quiescence barrier blocks a new call boundary");
		if (blockOnStop && blockPredecessor && campaign.scheduler().slots().stream().anyMatch(slot ->
			slot.fence() < fence && slot.state() != CallSlot.State.ADOPTED))
			throw new IOException("Unreconciled predecessor call blocks a new call boundary");
	}

	/**
	 * Decides whether a pending stop still applies to this owner rather than remaining historical evidence.
	 *
	 * @param campaign current locked campaign
	 * @param stop retained stop
	 * @return true if a call must await its resolution
	 */
	private boolean blocksCurrentClaim(Campaign campaign, StopRequest stop)
	{
		if (stop.status() != StopRequest.Status.PENDING)
			return false;
		if (stop.adoptingFence().isPresent())
			return true;
		if (stop.sourceFence().isEmpty())
			return stop.sourceInvocationId().equals(invocationId);
		long sourceFence = stop.sourceFence().orElseThrow();
		if (sourceFence == fence && !stop.sourceInvocationId().equals(invocationId))
			return false;
		InvocationStopRegistration source = campaign.scheduler().invocations().stream().filter(registration ->
			registration.invocationId().equals(stop.sourceInvocationId())).findFirst().orElse(null);
		if (source == null)
			return true;
		return source.registeredCounter() == campaign.transitionCounter() ||
			campaign.scheduler().slots().stream().anyMatch(slot -> slot.fence() == sourceFence);
	}

	/** Computes one call-slot state change. */
	@FunctionalInterface
	private interface SlotMutation
	{
		/**
		 * Applies one guarded slot state change.
		 *
		 * @param campaign current locked campaign
		 * @param slot current slot
		 * @return replacement slot
		 * @throws IOException if the transition is invalid
		 */
		CallSlot apply(Campaign campaign, CallSlot slot) throws IOException;
	}

	/**
	 * Verifies that this owner still retains its live lock.
	 *
	 * @throws IOException if this lease was closed or displaced
	 */
	void requireLive() throws IOException
	{
		if (!channel.isOpen() || !lock.isValid())
			throw new IOException("Scheduler lease is no longer live: " + ownerId);
	}

	/**
	 * Releases the exclusive owner lock.
	 *
	 * @throws IOException if lock release fails
	 */
	@Override
	public void close() throws IOException
	{
		if (!closed.compareAndSet(false, true))
			return;
		try (FileChannel ownedChannel = channel)
		{
			if (ownedChannel.isOpen() && lock.isValid())
				lock.release();
		}
		finally
		{
			localOwner.release();
		}
	}
}
