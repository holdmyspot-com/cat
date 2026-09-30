package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import java.io.IOException;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Retains native generation completion separately from semantic candidate admission. */
public final class CandidateGenerationCompletionService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds completion to the registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public CandidateGenerationCompletionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes one native settlement against the original generation request and claim.
	 *
	 * @param lease current scheduler owner
	 * @param requestRef indexed semantic generation request
	 * @param completion native settlement
	 * @return indexed native completion
	 * @throws IOException if retained authority or native evidence conflicts
	 */
	public EvidenceRef publishCompletion(SchedulerLease lease, EvidenceRef requestRef,
		NestedRunCompletion completion) throws IOException
	{
		Objects.requireNonNull(completion, "completion");
		return publishRetainedCompletion(lease, load(lease, requestRef), completion);
	}

	/**
	 * Publishes settlement using authority already validated by this service in the current operation.
	 *
	 * @param lease current scheduler owner
	 * @param call source-validated original call
	 * @param completion native settlement
	 * @return indexed native completion
	 * @throws IOException if native evidence or current scheduler authority conflicts
	 */
	EvidenceRef publishRetainedCompletion(SchedulerLease lease, RetainedGenerationCall call,
		NestedRunCompletion completion) throws IOException
	{
		lease.requireLive();
		return new NativeCompletionPublisher(store).publish(lease.binding(), call.claimed(), call.preStart(),
			call.request().asNested(), completion);
	}

	/**
	 * Adopts the indexed native settlement without admitting a semantic candidate.
	 *
	 * @param lease current scheduler owner
	 * @param requestRef indexed semantic generation request
	 * @return retained execution record and adopted native settlement
	 * @throws IOException if the original call or indexed completion conflicts
	 */
	public CandidateGenerationCallReconciliation.Settled adoptIndexed(SchedulerLease lease, EvidenceRef requestRef)
		throws IOException
	{
		return adoptRetainedCompletion(lease, load(lease, requestRef));
	}

	/**
	 * Adopts indexed settlement using authority already validated by this service in the current operation.
	 *
	 * @param lease current scheduler owner
	 * @param call source-validated original call
	 * @return adopted execution record and native settlement
	 * @throws IOException if indexed completion or current scheduler authority conflicts
	 */
	CandidateGenerationCallReconciliation.Settled adoptRetainedCompletion(SchedulerLease lease,
		RetainedGenerationCall call) throws IOException
	{
		lease.requireLive();
		String callId = call.semantic().logicalCallId();
		NativeCompletionPublisher.IndexedCompletion indexed = new NativeCompletionPublisher(store).
			readIndexed(lease.binding(), call.claimed(), call.preStart(), call.request().asNested());
		CandidateGenerationExecutionRecord record = new CandidateGenerationExecutionRecord(
			CandidateGenerationExecutionRecord.SCHEMA_VERSION, lease.binding().campaignId(),
			call.request().semanticRequestRef(),
			call.preStart().producerAttemptRef(), indexed.reference());
		EvidenceRef recordRef = store.appendImmutableJson(CandidateGenerationExecutionRecord.COLLECTION,
			JSON.writeValueAsBytes(record));
		MeasurementConditions conditions = new NativeCompletionMeasurements(store).conditions(lease.binding(),
			call.claimed(), call.preStart(), call.semantic().measurementEpochRef(), indexed.evidence());
		if (call.claimed().slot().state() != CallSlot.State.ADOPTED)
			lease.settle(callId, indexed.reference());
		lease.adopt(callId, recordRef);
		locator.openMeasurementObserver(lease.binding()).afterCall(lease, callId, conditions);
		return new CandidateGenerationCallReconciliation.Settled(recordRef, indexed.settlement());
	}

	/**
	 * Reopens the original generation request, claim, and indexed native start without resetting authored files.
	 *
	 * @param lease current scheduler owner
	 * @param requestRef original indexed semantic request
	 * @return verified retained generation authority
	 * @throws IOException if its campaign, evidence, or native identity conflicts
	 */
	RetainedGenerationCall load(SchedulerLease lease, EvidenceRef requestRef) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(requestRef, "requestRef");
		lease.requireLive();
		if (!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Candidate generation completion belongs to another campaign store");
		CandidateGenerationRequest semantic = new CandidateGenerationRequestService(locator, store).read(requestRef);
		String callId = semantic.logicalCallId();
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), callId);
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(), callId).
			orElseThrow(() -> new IOException("Candidate generation pre-start is missing"));
		CandidateGenerationRunRequest request = new CandidateGenerationRunRequestService(locator, store).
			readWithSemantic(claimed.slot().requestRef(), semantic);
		if (!request.semanticRequestRef().equals(requestRef) ||
			!request.campaignId().equals(lease.binding().campaignId()) ||
			!request.logicalCallId().equals(callId) || preStart.producerAttemptKind() != ProducerAttemptKind.CANDIDATE)
			throw new IOException("Candidate generation completion conflicts with its retained native start");
		return new RetainedGenerationCall(semantic, claimed, preStart, request);
	}

	/**
	 * Original generation authority retained across scheduler owners.
	 *
	 * @param semantic indexed authoring request
	 * @param claimed original slot and claim
	 * @param preStart indexed native run identity
	 * @param request source-backed native request
	 */
	record RetainedGenerationCall(CandidateGenerationRequest semantic, ClaimedSlot claimed,
		CallPreStartRecord preStart, CandidateGenerationRunRequest request)
	{
	}
}
