package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sole evidence-backed lifecycle transition boundary for the pinned campaign store.
 */
public final class CampaignTransitions
{
	private final CampaignStore store;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Binds transitions to one pinned campaign store.
	 *
	 * @param store pinned campaign store
	 */
	public CampaignTransitions(CampaignStore store)
	{
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Validates a request and applies an integrated producer transition.
	 *
	 * @param request inert transition request
	 * @return committed result
	 * @throws IOException if the binding or counter is stale, or the producer is unavailable
	 */
	public TransitionResult apply(TransitionRequest request) throws IOException
	{
		Objects.requireNonNull(request, "request");
		if (request.kind() == TransitionKind.INTERRUPTION)
			return store.commitInterruption(request);
		if (request.kind() == TransitionKind.CANCELLATION)
			return store.commitMeasurementPauseCancellation(request);
		Campaign current = store.lockedSnapshot(request.binding());
		if (current.transitionCounter() > request.expectedCounter())
		{
			for (TransitionReceipt receipt : current.transitionReceipts())
			{
				if (receipt.expectedCounter() == request.expectedCounter())
				{
					if (receipt.binding().equals(request.binding()) && receipt.kind() == request.kind() &&
						receipt.producerRecordRef().equals(request.producerRecordRef()))
					{
						store.readImmutableJson(request.producerRecordRef());
						return receipt.result();
					}
					throw new IOException("Conflicting campaign transition for committed counter: " +
						request.expectedCounter());
				}
			}
		}
		if (current.transitionCounter() != request.expectedCounter())
			throw new IOException("Stale campaign transition counter: " + request.expectedCounter());
		if (request.kind() == TransitionKind.SEMANTIC_OUTCOME)
		{
			new InitialTrialOutcomeService(new CampaignLocator(store.directory().getParent()), store).
				requireIndexedOutcome(request.producerRecordRef());
			return store.commitSemanticOutcome(request);
		}
		requireIntegratedProducer(request.kind());
		throw new IOException("Integrated producer has no transition implementation: " + request.kind());
	}

	/**
	 * Adopts a verified first candidate into the current confirmed campaign.
	 *
	 * @param binding exact pinned campaign
	 * @param candidateRef retained first-candidate producer
	 * @return original committed result on an equal retry
	 * @throws IOException if the candidate or active claim is missing, stale, or conflicting
	 */
	public TransitionResult adoptInitialCandidate(CampaignBinding binding, EvidenceRef candidateRef)
		throws IOException
	{
		new InitialCandidateService(new CampaignLocator(store.directory().getParent()), store).
			verifyIndexedReference(candidateRef);
		return store.commitInitialCandidate(binding, candidateRef);
	}

	/**
	 * Commits an indexed development draw before native trial execution.
	 *
	 * @param binding exact pinned campaign
	 * @param assignmentRef immutable selection record
	 * @return original committed result on an equal retry
	 * @throws IOException if the draw or current campaign conflicts
	 */
	public TransitionResult assignDevelopmentTrial(CampaignBinding binding, EvidenceRef assignmentRef)
		throws IOException
	{
		return store.commitTrialAssignment(binding, assignmentRef);
	}

	/**
	 * Rejects a dormant producer before callers write any key transition intent.
	 *
	 * @param kind finite lifecycle kind
	 * @throws UnavailableTransitionException until its producer schema and validator are integrated
	 */
	public static void requireIntegratedProducer(TransitionKind kind) throws UnavailableTransitionException
	{
		Objects.requireNonNull(kind, "kind");
		if (kind == TransitionKind.INTERRUPTION || kind == TransitionKind.CANCELLATION ||
			kind == TransitionKind.SEMANTIC_OUTCOME)
			return;
		throw new UnavailableTransitionException(kind, producer(kind));
	}

	/**
	 * Reads one pinned state-lock snapshot of the active confirmed claim.
	 *
	 * @param binding exact campaign binding
	 * @return current confirmed claim
	 * @throws IOException if the binding is foreign or the campaign is unconfirmed
	 */
	public CurrentClaim readCurrentClaim(CampaignBinding binding) throws IOException
	{
		Campaign current = store.lockedSnapshot(binding);
		String activeLineageId = current.activeLineageId();
		if (activeLineageId == null)
			throw new IOException("Campaign has no confirmed claim: " + current.campaignId());
		LineageSummary active = current.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(activeLineageId)).findFirst().orElseThrow(() ->
			new IOException("Active lineage is missing: " + activeLineageId));
		return new CurrentClaim(current.campaignId(), activeLineageId, active.confirmedContractId(),
			current.transitionCounter());
	}

	/**
	 * Reads the exact current preparation authority without creating a cycle or call.
	 *
	 * @param binding exact pinned campaign binding
	 * @return initial or proven revision preparation scope
	 * @throws IOException if the campaign has no current preparation authority
	 */
	public PreparationAuthority readPreparationAuthority(CampaignBinding binding) throws IOException
	{
		Campaign campaign = store.lockedSnapshot(binding);
		if (campaign.status() == CampaignStatus.AWAITING_CONTRACT_CONFIRMATION &&
			campaign.activeLineageId() == null && campaign.lineages().isEmpty())
		{
			if (campaign.scheduler().barrier().isPresent() ||
				campaign.preparationScope().revisionProofRef().isPresent())
				throw new IOException("Initial preparation has a foreign revision scope");
			if (campaign.preparationScope().sourceChangeRef().isPresent())
				store.readImmutableJson(campaign.preparationScope().sourceChangeRef().orElseThrow());
			if (campaign.preparationScope().generation() > 1)
				throw new IOException("Preparation source-change authority is unavailable until C4 verification");
			return new PreparationAuthority(campaign.campaignId(), PreparationAuthority.Kind.INITIAL,
				Optional.empty(), Optional.empty(), campaign.preparationScope().generation(),
				campaign.preparationScope().sourceChangeRef(), campaign.transitionCounter());
		}
		if (campaign.status() == CampaignStatus.DEVELOPING || campaign.status() == CampaignStatus.FORMAL_TESTING)
		{
			SchedulerBarrier barrier = campaign.scheduler().barrier().orElseThrow(() ->
				new IOException("Revision preparation requires a proven quiescence barrier"));
			if (barrier.purpose() != QuiescencePurpose.CONTRACT_REVISION ||
				!Objects.equals(barrier.lineage(), campaign.activeLineageId()) ||
				barrier.phase() != campaign.status() ||
				barrier.predecessorCounter() > campaign.transitionCounter())
				throw new IOException("Revision preparation barrier conflicts with the active lineage or phase");
			EvidenceRef proofRef = barrier.proofRef().orElseThrow(() ->
				new IOException("Revision preparation requires a proven quiescence barrier"));
			verifyRevisionProof(binding, campaign, barrier, proofRef);
			LineageSummary active = campaign.lineages().stream().filter(lineage ->
				lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow(() ->
				new IOException("Revision preparation active lineage is missing"));
			PreparationScope scope = campaign.preparationScope();
			if (!scope.revisionProofRef().equals(Optional.of(proofRef)))
				scope = new PreparationScope(Optional.of(proofRef), 1, Optional.empty());
			if (scope.sourceChangeRef().isPresent())
				store.readImmutableJson(scope.sourceChangeRef().orElseThrow());
			if (scope.generation() > 1)
				throw new IOException("Preparation source-change authority is unavailable until C4 verification");
			return new PreparationAuthority(campaign.campaignId(), PreparationAuthority.Kind.REVISION,
				Optional.of(new LineageKey(active.lineageId(), active.confirmedContractId())),
				Optional.of(proofRef), scope.generation(), scope.sourceChangeRef(), campaign.transitionCounter());
		}
		throw new IOException("Campaign has no current preparation authority");
	}

	/**
	 * Validates a retained C1 revision proof and its adopted historical slot root.
	 *
	 * @param binding exact pinned campaign
	 * @param campaign locked campaign snapshot
	 * @param barrier active revision barrier
	 * @param proofRef retained proof reference
	 * @throws IOException if the proof, root, or campaign identity conflicts
	 */
	private void verifyRevisionProof(CampaignBinding binding, Campaign campaign, SchedulerBarrier barrier,
		EvidenceRef proofRef) throws IOException
	{
		if (!proofRef.collection().equals("scheduler-quiescence-proofs"))
			throw new IOException("Revision preparation proof has the wrong collection");
		try
		{
			byte[] bytes = store.readImmutableJson(proofRef);
			SchedulerQuiescenceProof proof = JSON.readValue(bytes, SchedulerQuiescenceProof.class);
			if (proof == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(proof)) ||
				!proof.binding().equals(binding) || proof.purpose() != QuiescencePurpose.CONTRACT_REVISION ||
				proof.predecessorCounter() != barrier.predecessorCounter() ||
				proof.schedulerFence() != barrier.schedulerFence() ||
				!proof.lineage().equals(barrier.lineage()) || proof.phase() != barrier.phase() ||
				!proof.callSlotRootRef().collection().equals("scheduler-call-slot-roots"))
				throw new IOException("Revision preparation proof conflicts with its barrier");
			byte[] rootBytes = store.readImmutableJson(proof.callSlotRootRef());
			CallSlot[] slots = JSON.readValue(rootBytes, CallSlot[].class);
			if (slots == null || !Arrays.equals(rootBytes, JSON.writeValueAsBytes(slots)) ||
				Arrays.stream(slots).anyMatch(slot -> slot.state() != CallSlot.State.ADOPTED) ||
				Arrays.stream(slots).map(CallSlot::logicalCallId).distinct().count() != slots.length)
				throw new IOException("Revision preparation proof has an incomplete adopted-slot root");
			for (CallSlot slot : slots)
			{
				store.verifySlotClaim(binding, campaign, slot);
				if (campaign.scheduler().slots().stream().noneMatch(current ->
					current.logicalCallId().equals(slot.logicalCallId()) &&
					current.slotClaimRef().equals(slot.slotClaimRef()) &&
					current.state() == CallSlot.State.ADOPTED))
					throw new IOException("Revision preparation proof lost an adopted slot");
			}
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Revision preparation proof is malformed", exception);
		}
	}

	/**
	 * Reads the embedded confirmation receipt committed at one predecessor counter.
	 *
	 * @param binding exact campaign binding
	 * @param expectedCounter confirmation predecessor counter
	 * @return the committed confirmation receipt, if present
	 * @throws IOException if the binding or campaign state is invalid
	 */
	public Optional<TransitionReceipt> readCommittedConfirmation(CampaignBinding binding,
		long expectedCounter) throws IOException
	{
		if (expectedCounter < 0)
			throw new IllegalArgumentException("expectedCounter must be non-negative");
		Campaign current = store.lockedSnapshot(binding);
		return current.transitionReceipts().stream().filter(receipt ->
			receipt.expectedCounter() == expectedCounter && receipt.binding().equals(binding) &&
				(receipt.kind() == TransitionKind.CONFIRMED_CONTRACT ||
					receipt.kind() == TransitionKind.REVISED_CONTRACT)).findFirst();
	}

	/**
	 * Names the evidence producer responsible for each finite kind.
	 *
	 * @param kind transition kind
	 * @return producer component
	 */
	private static String producer(TransitionKind kind)
	{
		return switch (kind)
		{
			case CONFIRMED_CONTRACT, REVISED_CONTRACT -> "C4";
			case INITIAL_CANDIDATE, DEVELOPMENT_ROOT, FORMAL_CANDIDATE_RESERVATION,
				CANDIDATE_REVISION, PLATEAU -> "C6";
			case CANDIDATE_APPLICATION, INFRASTRUCTURE_FAILURE, CERTIFICATION, INTERRUPTION,
				CANCELLATION, RESUMPTION -> "C7";
			case FORMALIZATION, TRIAL_ASSIGNMENT, TRIAL_START, RETRYABLE_FAILURE, INVALID_TRIAL,
				SEMANTIC_OUTCOME, FAILED_CANDIDATE, FORMAL_UNAVAILABLE -> "C5";
			case MEASUREMENT_CHANGED, MEASUREMENT_RESTORED, NEW_MEASUREMENT, BASELINE_ADVANCE -> "C8";
		};
	}
}
