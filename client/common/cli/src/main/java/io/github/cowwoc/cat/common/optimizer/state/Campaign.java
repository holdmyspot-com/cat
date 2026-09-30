package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable durable summary of one optimizer campaign.
 *
 * @param schemaVersion schema discriminator
 * @param campaignId campaign identity
 * @param status lifecycle phase
 * @param activeLineageId active lineage
 * @param currentCandidateId current candidate
 * @param appliedCandidateId candidate reconciled into the user worktree
 * @param activeDevelopmentRoot current development evidence root
 * @param transitionCounter concurrency counter
 * @param closedPhase resumable phase
 * @param lineages immutable lineages
 * @param trials logical trials
 * @param history audit history
 * @param optimizationProfile immutable model and reasoning profile selected for optimization
 * @param transitionReceipts append-preserving embedded transition commit authorities
 * @param scheduler durable invocation, stop, and owner-fence state
 * @param preparationScope generation and source-change predecessor for one proof scope
 * @param activeFormalFailureTriggerRef failed formal categories that constrain the next development round
 * @param measurementState active measurement epoch and material-change pause, if established
 * @param terminalResults immutable certified and plateau result history
 * @param acknowledgedInterruptionStopRef active acknowledged host or token stop, if any
 */
public record Campaign(int schemaVersion, String campaignId, CampaignStatus status, String activeLineageId,
	String currentCandidateId, String appliedCandidateId, EvidenceRef activeDevelopmentRoot, long transitionCounter,
	CampaignStatus closedPhase,
	List<LineageSummary> lineages,
	List<TrialState> trials, List<String> history, ModelProfile optimizationProfile,
	List<TransitionReceipt> transitionReceipts, SchedulerState scheduler, PreparationScope preparationScope,
	EvidenceRef activeFormalFailureTriggerRef, MeasurementState measurementState,
	List<TerminalResultRef> terminalResults, EvidenceRef acknowledgedInterruptionStopRef)
{
	/** The only supported campaign schema version. */
	public static final int SCHEMA_VERSION = 3;

	/** Validates and defensively copies durable collections. */
	public Campaign
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported campaign schema version: " + schemaVersion);
		if (campaignId == null || campaignId.isBlank() || status == null || transitionCounter < 0)
			throw new IllegalArgumentException("Campaign identity, status, and transition counter are invalid");
		if (currentCandidateId != null && !currentCandidateId.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Current candidate id must be a lowercase SHA-256 id");
		if (appliedCandidateId != null && !appliedCandidateId.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Applied candidate id must be a lowercase SHA-256 id");
		lineages = List.copyOf(lineages);
		trials = List.copyOf(trials);
		history = List.copyOf(history);
		transitionReceipts = List.copyOf(transitionReceipts);
		terminalResults = List.copyOf(terminalResults);
		if (scheduler == null)
			throw new NullPointerException("scheduler");
		Objects.requireNonNull(preparationScope, "preparationScope");
		if (activeFormalFailureTriggerRef != null &&
			!activeFormalFailureTriggerRef.collection().equals("formal-failure-round-triggers"))
		{
			throw new IllegalArgumentException("Formal failure trigger has the wrong collection");
		}
		if (acknowledgedInterruptionStopRef != null &&
			!acknowledgedInterruptionStopRef.collection().equals("stop-requests"))
		{
			throw new IllegalArgumentException("Acknowledged stop has the wrong collection");
		}
		long previousReceipt = -1;
		for (TransitionReceipt receipt : transitionReceipts)
		{
			if (!receipt.binding().campaignId().equals(campaignId) ||
				previousReceipt >= 0 && receipt.expectedCounter() != previousReceipt ||
				receipt.resultingCounter() > transitionCounter)
				throw new IllegalArgumentException("Campaign transition receipts are foreign or out of order");
			previousReceipt = receipt.resultingCounter();
		}
		boolean closed = status == CampaignStatus.INTERRUPTED || status == CampaignStatus.CANCELLED;
		if (closed != (closedPhase != null))
			throw new IllegalArgumentException("Only interrupted or cancelled campaigns retain a closed phase");
		if (closedPhase != null && closedPhase != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION &&
			closedPhase != CampaignStatus.DEVELOPING && closedPhase != CampaignStatus.FORMAL_TESTING)
		{
			throw new IllegalArgumentException("Closed phase must name a resumable active phase");
		}
		boolean unconfirmed = status == CampaignStatus.AWAITING_CONTRACT_CONFIRMATION ||
			closed && closedPhase == CampaignStatus.AWAITING_CONTRACT_CONFIRMATION;
		if (activeLineageId == null)
		{
			if (!unconfirmed || !lineages.isEmpty() || currentCandidateId != null || appliedCandidateId != null ||
				activeDevelopmentRoot != null ||
				!trials.isEmpty())
				throw new IllegalArgumentException("Only an unconfirmed campaign may have no active lineage");
		}
		else if (unconfirmed)
			throw new IllegalArgumentException("An unconfirmed campaign may not have an active lineage");
		else if (lineages.stream().filter(lineage -> lineage.lineageId().equals(activeLineageId)).count() != 1)
			throw new IllegalArgumentException("Active lineage must resolve exactly once: " + activeLineageId);
		if (activeDevelopmentRoot != null && unconfirmed)
			throw new IllegalArgumentException("An unconfirmed campaign may not have development evidence");
		if (unconfirmed && (activeFormalFailureTriggerRef != null || measurementState != null ||
			!terminalResults.isEmpty() || acknowledgedInterruptionStopRef != null &&
			status != CampaignStatus.INTERRUPTED))
		{
			throw new IllegalArgumentException("An unconfirmed campaign may not have optimization results");
		}
		if (lineages.stream().map(LineageSummary::lineageId).distinct().count() != lineages.size())
			throw new IllegalArgumentException("Campaign contains duplicate lineage ids");
		long previousTerminalCounter = 0;
		for (TerminalResultRef terminal : terminalResults)
		{
			LineageSummary terminalLineage = lineages.stream().filter(lineage -> lineage.lineageId().equals(
				terminal.lineageId())).findFirst().orElseThrow(() ->
				new IllegalArgumentException("Terminal result references a missing lineage: " +
					terminal.lineageId()));
			if (terminal.kind() == TerminalResultRef.Kind.CERTIFIED && terminalLineage.candidates().stream().noneMatch(
				allowance -> allowance.candidateId().equals(terminal.candidateId().orElseThrow())))
			{
				throw new IllegalArgumentException("Certified terminal candidate is not reserved in its lineage");
			}
			if (terminal.resultingCounter() > transitionCounter)
				throw new IllegalArgumentException("Terminal result counter exceeds campaign counter");
			if (terminal.resultingCounter() <= previousTerminalCounter)
				throw new IllegalArgumentException("Terminal result counters must increase");
			previousTerminalCounter = terminal.resultingCounter();
		}
		if (terminalResults.stream().map(TerminalResultRef::terminalRootRef).distinct().count() !=
			terminalResults.size())
		{
			throw new IllegalArgumentException("Campaign contains duplicate terminal result roots");
		}
		if (trials.stream().map(TrialState::logicalTrialId).distinct().count() != trials.size())
			throw new IllegalArgumentException("Campaign contains duplicate logical trial ids");
		for (TrialState trial : trials)
			validateTrialReference(trial, lineages);
	}

	/**
	 * Creates a production campaign with its immutable optimization profile.
	 *
	 * @param campaignId stable campaign identity
	 * @param optimizationProfile selected optimization model profile
	 * @return initial durable campaign
	 */
	public static Campaign create(String campaignId, ModelProfile optimizationProfile)
	{
		if (optimizationProfile == null)
			throw new NullPointerException("optimizationProfile");
		return new Campaign(SCHEMA_VERSION, campaignId, CampaignStatus.AWAITING_CONTRACT_CONFIRMATION, null, null,
			null, null, 0, null, List.of(), List.of(), List.of(), optimizationProfile, List.of(),
			SchedulerState.initial(), PreparationScope.initial(), null, null, List.of(), null);
	}

	/**
	 * Replaces only embedded scheduler state without advancing the lifecycle counter.
	 *
	 * @param nextScheduler scheduler state after one atomic mutation
	 * @return campaign with the same lifecycle authority
	 */
	Campaign withScheduler(SchedulerState nextScheduler)
	{
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, transitionCounter, closedPhase, lineages, trials, history,
			optimizationProfile, transitionReceipts, nextScheduler, preparationScope, activeFormalFailureTriggerRef,
			measurementState, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Commits an acknowledged stop and its active pointer in the same campaign record.
	 *
	 * @param nextScheduler scheduler with the exact stop acknowledged
	 * @param stopRef acknowledged stop reference
	 * @return campaign with the atomic acknowledgment
	 */
	Campaign withAcknowledgedInterruptionStop(SchedulerState nextScheduler, EvidenceRef stopRef)
	{
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, transitionCounter, closedPhase, lineages, trials, history,
			optimizationProfile, transitionReceipts, nextScheduler, preparationScope, activeFormalFailureTriggerRef,
			measurementState, terminalResults, stopRef);
	}

	/**
	 * Replaces only the durable preparation scope without a lifecycle transition.
	 *
	 * @param nextScope exact next preparation scope
	 * @return campaign with unchanged lifecycle state
	 */
	Campaign withPreparationScope(PreparationScope nextScope)
	{
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, transitionCounter, closedPhase, lineages, trials, history,
			optimizationProfile, transitionReceipts, scheduler, nextScope, activeFormalFailureTriggerRef,
			measurementState, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Commits an interruption while preserving the phase and all work needed for resumption.
	 *
	 * @param receipt verified interruption transition receipt
	 * @return interrupted campaign with its embedded commit authority
	 */
	Campaign withInterruption(TransitionReceipt receipt)
	{
		if (status != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION &&
			status != CampaignStatus.DEVELOPING && status != CampaignStatus.FORMAL_TESTING ||
			receipt.kind() != TransitionKind.INTERRUPTION ||
			receipt.expectedCounter() != transitionCounter)
			throw new IllegalArgumentException("Interruption does not match an active campaign predecessor");
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		return new Campaign(schemaVersion, campaignId, CampaignStatus.INTERRUPTED, activeLineageId,
			currentCandidateId, appliedCandidateId, activeDevelopmentRoot, receipt.resultingCounter(), status,
			lineages, trials, history, optimizationProfile, nextReceipts, scheduler, preparationScope,
			activeFormalFailureTriggerRef, measurementState, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Closes a paused confirmed campaign while preserving its measurement and lineage evidence.
	 *
	 * @param receipt verified cancellation transition
	 * @return cancelled campaign retaining its resumable phase and paused measurement
	 */
	Campaign withMeasurementPauseCancellation(TransitionReceipt receipt)
	{
		if (status != CampaignStatus.DEVELOPING && status != CampaignStatus.FORMAL_TESTING ||
			measurementState == null || measurementState.pause().isEmpty() ||
			receipt.kind() != TransitionKind.CANCELLATION ||
			receipt.expectedCounter() != transitionCounter ||
			receipt.result().phase() != CampaignStatus.CANCELLED ||
			!Objects.equals(receipt.result().lineageId(), activeLineageId))
			throw new IllegalArgumentException("Cancellation does not match a paused confirmed campaign");
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		return new Campaign(schemaVersion, campaignId, CampaignStatus.CANCELLED, activeLineageId,
			currentCandidateId, appliedCandidateId, activeDevelopmentRoot, receipt.resultingCounter(), status,
			lineages, trials, history, optimizationProfile, nextReceipts, scheduler, preparationScope,
			activeFormalFailureTriggerRef, measurementState, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Activates the first confirmed lineage and its measurement epoch in one campaign revision.
	 *
	 * @param receipt verified confirmation transition
	 * @param contractId validated confirmed-contract identity
	 * @param epochRef retained measurement epoch
	 * @return developing campaign with one confirmed lineage
	 */
	Campaign withConfirmedContract(TransitionReceipt receipt, String contractId, EvidenceRef epochRef)
	{
		if (status != CampaignStatus.AWAITING_CONTRACT_CONFIRMATION || activeLineageId != null ||
			!lineages.isEmpty() || receipt.kind() != TransitionKind.CONFIRMED_CONTRACT ||
			receipt.expectedCounter() != transitionCounter ||
			receipt.result().phase() != CampaignStatus.DEVELOPING ||
			receipt.result().lineageId() == null ||
			!receipt.producerRecordRef().equals(new EvidenceRef("contracts", contractId)))
			throw new IllegalArgumentException("Confirmation does not match an unconfirmed campaign predecessor");
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		LineageSummary lineage = new LineageSummary(receipt.result().lineageId(), contractId, 1, List.of());
		return new Campaign(schemaVersion, campaignId, CampaignStatus.DEVELOPING, lineage.lineageId(), null,
			null, null, receipt.resultingCounter(), null, List.of(lineage), List.of(), history,
			optimizationProfile, nextReceipts, scheduler, preparationScope, null,
			new MeasurementState(epochRef, Optional.empty(), Optional.empty()), terminalResults, null);
	}

	/**
	 * Selects the retained first candidate for the active confirmed lineage.
	 *
	 * @param receipt committed initial-candidate transition
	 * @param candidate verified source-backed first candidate
	 * @return developing campaign with a selected candidate
	 */
	Campaign withInitialCandidate(TransitionReceipt receipt, InitialCandidate candidate)
	{
		if (status != CampaignStatus.DEVELOPING || currentCandidateId != null ||
			appliedCandidateId != null || !trials.isEmpty() ||
			!campaignId.equals(candidate.campaignId()) ||
			!Objects.equals(activeLineageId, candidate.lineageId()) ||
			lineages.stream().noneMatch(lineage -> lineage.lineageId().equals(candidate.lineageId()) &&
				lineage.confirmedContractId().equals(candidate.contractId())) ||
			receipt.kind() != TransitionKind.INITIAL_CANDIDATE ||
			receipt.expectedCounter() != transitionCounter ||
			receipt.result().phase() != CampaignStatus.DEVELOPING ||
			!Objects.equals(receipt.result().lineageId(), activeLineageId) ||
			!Objects.equals(receipt.result().candidateId(), candidate.candidateSnapshotId()))
			throw new IllegalArgumentException("Initial candidate does not match the active confirmed lineage");
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		return new Campaign(schemaVersion, campaignId, status, activeLineageId,
			candidate.candidateSnapshotId(), appliedCandidateId, activeDevelopmentRoot,
			receipt.resultingCounter(), closedPhase, lineages, trials, history, optimizationProfile,
			nextReceipts, scheduler, preparationScope, activeFormalFailureTriggerRef, measurementState,
			terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Commits one development trial selected for the current candidate before native start.
	 *
	 * @param receipt exact committed assignment transition
	 * @param assignment verified concrete trial assignment
	 * @return campaign with one additional assigned trial
	 */
	Campaign withTrialAssignment(TransitionReceipt receipt, TrialAssignment assignment)
	{
		if (status != CampaignStatus.DEVELOPING || receipt.kind() != TransitionKind.TRIAL_ASSIGNMENT ||
			receipt.expectedCounter() != transitionCounter ||
			!campaignId.equals(assignment.campaignId()) ||
			!Objects.equals(activeLineageId, assignment.lineage().lineageId()) ||
			!Objects.equals(currentCandidateId, assignment.candidateId()) ||
			trials.stream().anyMatch(trial -> trial.logicalTrialId().equals(assignment.logicalTrialId())))
			throw new IllegalArgumentException("Trial assignment conflicts with the active campaign");
		List<TrialState> nextTrials = new ArrayList<>(trials);
		nextTrials.add(new TrialState(assignment.logicalTrialId(), assignment.lineage().lineageId(),
			assignment.candidateId(), EvidencePurpose.DEVELOPMENT, TrialStatus.ASSIGNED, null,
			receipt.producerRecordRef(), null));
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, receipt.resultingCounter(), closedPhase, lineages,
			nextTrials, history, optimizationProfile, nextReceipts, scheduler, preparationScope,
			activeFormalFailureTriggerRef, measurementState, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Applies one validated evaluator label to its previously assigned development trial.
	 *
	 * @param receipt exact semantic outcome transition
	 * @param assignment trial selected before the native calls
	 * @param outcome validated evaluator judgment for that assignment
	 * @return campaign with one terminal trial and its retained outcome
	 */
	Campaign withSemanticOutcome(TransitionReceipt receipt, TrialAssignment assignment,
		TrialOutcomeRecord outcome)
	{
		if (status != CampaignStatus.DEVELOPING || receipt.kind() != TransitionKind.SEMANTIC_OUTCOME ||
			receipt.expectedCounter() != transitionCounter ||
			!campaignId.equals(assignment.campaignId()) || !campaignId.equals(outcome.campaignId()) ||
			!Objects.equals(activeLineageId, assignment.lineage().lineageId()) ||
			!Objects.equals(currentCandidateId, assignment.candidateId()) ||
			!outcome.lineage().equals(assignment.lineage()) ||
			!outcome.assignmentRef().collection().equals("trial-assignments"))
			throw new IllegalArgumentException("Semantic outcome conflicts with the active development trial");
		TrialStatus terminalStatus = switch (outcome.response().label())
		{
			case COMPLIANT -> TrialStatus.COMPLIANT;
			case NONCOMPLIANT -> TrialStatus.NONCOMPLIANT;
		};
		List<TrialState> nextTrials = new ArrayList<>(trials);
		int trialIndex = -1;
		for (int index = 0; index < nextTrials.size(); ++index)
			if (nextTrials.get(index).logicalTrialId().equals(assignment.logicalTrialId()))
			{
				trialIndex = index;
				break;
			}
		if (trialIndex < 0)
			throw new IllegalArgumentException("Semantic outcome has no assigned trial");
		TrialState trial = nextTrials.get(trialIndex);
		if (trial.status() != TrialStatus.ASSIGNED ||
			!trial.selectionEvidence().equals(outcome.assignmentRef()) ||
			!trial.lineageId().equals(assignment.lineage().lineageId()) ||
			!trial.candidateId().equals(assignment.candidateId()))
			throw new IllegalArgumentException("Semantic outcome conflicts with retained trial state");
		nextTrials.set(trialIndex, new TrialState(trial.logicalTrialId(), trial.lineageId(), trial.candidateId(),
			trial.purpose(), terminalStatus, TrialState.expectedFailureKind(terminalStatus),
			trial.selectionEvidence(), receipt.producerRecordRef()));
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, receipt.resultingCounter(), closedPhase, lineages,
			nextTrials, history, optimizationProfile, nextReceipts, scheduler, preparationScope,
			activeFormalFailureTriggerRef, measurementState, terminalResults,
			acknowledgedInterruptionStopRef);
	}

	/**
	 * Advances the accepted dynamic baseline without changing the lifecycle counter.
	 *
	 * @param observationRef complete before-call observation
	 * @return campaign whose active baseline is the accepted observation
	 */
	Campaign withMeasurementBaseline(EvidenceRef observationRef)
	{
		if (measurementState == null || measurementState.pause().isPresent() ||
			status != CampaignStatus.DEVELOPING && status != CampaignStatus.FORMAL_TESTING ||
			!observationRef.collection().equals("dynamic-baseline-roots"))
			throw new IllegalArgumentException("Measurement baseline requires an active unpaused claim");
		MeasurementState next = new MeasurementState(measurementState.epochRef(),
			Optional.of(observationRef), Optional.empty());
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, transitionCounter, closedPhase, lineages, trials, history,
			optimizationProfile, transitionReceipts, scheduler, preparationScope, activeFormalFailureTriggerRef,
			next, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Commits a known admission change and closes further call admission.
	 *
	 * @param receipt verified material-change transition
	 * @param changeRef immutable change comparison
	 * @return campaign paused at its existing measurement baseline
	 */
	Campaign withMeasurementChange(TransitionReceipt receipt, EvidenceRef changeRef)
	{
		if (measurementState == null || measurementState.baselineRootRef().isEmpty() ||
			measurementState.pause().isPresent() || receipt.kind() != TransitionKind.MEASUREMENT_CHANGED ||
			receipt.expectedCounter() != transitionCounter || receipt.result().phase() != status ||
			!Objects.equals(receipt.result().lineageId(), activeLineageId) ||
			!receipt.producerRecordRef().equals(changeRef))
			throw new IllegalArgumentException("Measurement change does not match an active baseline");
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		MeasurementPause pause = new MeasurementPause(changeRef, MeasurementPause.Origin.ADMISSION, status);
		MeasurementState next = new MeasurementState(measurementState.epochRef(),
			measurementState.baselineRootRef(), Optional.of(pause));
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, receipt.resultingCounter(), closedPhase, lineages, trials,
			history, optimizationProfile, nextReceipts, scheduler, preparationScope, activeFormalFailureTriggerRef,
			next, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Clears a known admission pause after the original conditions are verified again.
	 *
	 * @param receipt verified restoration transition
	 * @param restorationRef immutable restoration proof
	 * @param observationRef accepted before-call observation
	 * @return campaign with the original epoch and restored dynamic baseline
	 */
	Campaign withMeasurementRestoration(TransitionReceipt receipt, EvidenceRef restorationRef,
		EvidenceRef observationRef)
	{
		if (measurementState == null || measurementState.pause().isEmpty() ||
			measurementState.pause().orElseThrow().origin() != MeasurementPause.Origin.ADMISSION ||
			receipt.kind() != TransitionKind.MEASUREMENT_RESTORED ||
			receipt.expectedCounter() != transitionCounter || receipt.result().phase() != status ||
			!Objects.equals(receipt.result().lineageId(), activeLineageId) ||
			!receipt.producerRecordRef().equals(restorationRef) ||
			!observationRef.collection().equals("dynamic-baseline-roots"))
			throw new IllegalArgumentException("Restoration does not match an active admission pause");
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		MeasurementState next = new MeasurementState(measurementState.epochRef(),
			Optional.of(observationRef), Optional.empty());
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, receipt.resultingCounter(), closedPhase, lineages, trials,
			history, optimizationProfile, nextReceipts, scheduler, preparationScope, activeFormalFailureTriggerRef,
			next, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Resolves an admission pause with a distinct epoch and its first accepted observation.
	 *
	 * @param receipt verified new-measurement transition
	 * @param epochRef distinct measurement identity
	 * @param observationRef accepted before-call observation under that identity
	 * @return campaign preserving earlier trials and lineage history
	 */
	Campaign withNewMeasurement(TransitionReceipt receipt, EvidenceRef epochRef,
		EvidenceRef observationRef)
	{
		if (measurementState == null || measurementState.pause().isEmpty() ||
			measurementState.pause().orElseThrow().origin() != MeasurementPause.Origin.ADMISSION ||
			receipt.kind() != TransitionKind.NEW_MEASUREMENT ||
			receipt.expectedCounter() != transitionCounter || receipt.result().phase() != status ||
			!Objects.equals(receipt.result().lineageId(), activeLineageId) ||
			!receipt.producerRecordRef().equals(observationRef) ||
			!epochRef.collection().equals("measurement-epochs") ||
			epochRef.equals(measurementState.epochRef()) ||
			!observationRef.collection().equals("dynamic-baseline-roots"))
			throw new IllegalArgumentException("New measurement does not match an active admission pause");
		List<TransitionReceipt> nextReceipts = new ArrayList<>(transitionReceipts);
		nextReceipts.add(receipt);
		MeasurementState next = new MeasurementState(epochRef, Optional.of(observationRef), Optional.empty());
		return new Campaign(schemaVersion, campaignId, status, activeLineageId, currentCandidateId,
			appliedCandidateId, activeDevelopmentRoot, receipt.resultingCounter(), closedPhase, lineages, trials,
			history, optimizationProfile, nextReceipts, scheduler, preparationScope, activeFormalFailureTriggerRef,
			next, terminalResults, acknowledgedInterruptionStopRef);
	}

	/**
	 * Validates a trial's lineage and formal reservation reference.
	 *
	 * @param trial the trial
	 * @param lineages all lineages
	 */
	private static void validateTrialReference(TrialState trial, List<LineageSummary> lineages)
	{
		LineageSummary lineage = lineages.stream().filter(value -> value.lineageId().equals(trial.lineageId())).
			findFirst().orElseThrow(() -> new IllegalArgumentException("Trial references missing lineage: " +
				trial.lineageId()));
		if (trial.purpose() == EvidencePurpose.FORMAL && lineage.candidates().stream().noneMatch(candidate ->
			candidate.candidateId().equals(trial.candidateId())))
		{
			throw new IllegalArgumentException("Formal trial references an unreserved candidate: " + trial.candidateId());
		}
	}
}
