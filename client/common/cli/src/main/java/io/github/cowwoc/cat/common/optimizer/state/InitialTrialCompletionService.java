package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunCompletion;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/** Retains a native trial completion before semantic evaluation. */
public final class InitialTrialCompletionService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds native completion to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public InitialTrialCompletionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes one typed native completion against the assigned trial's original claim.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef committed trial assignment
	 * @param completion validated native settlement
	 * @return indexed native completion
	 * @throws IOException if completion evidence conflicts with its retained call
	 */
	public EvidenceRef publishCompletion(SchedulerLease lease, EvidenceRef assignmentRef,
		NestedRunCompletion completion) throws IOException
	{
		Objects.requireNonNull(completion, "completion");
		RetainedTrialCall call = load(lease, assignmentRef);
		return new NativeCompletionPublisher(store).publish(lease.binding(), call.claimed(), call.preStart(),
			call.request().asNested(), completion);
	}

	/**
	 * Adopts an indexed native completion without assigning it a semantic outcome.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef committed trial assignment
	 * @return retained execution record
	 * @throws IOException if the original call or completion index is invalid
	 */
	public EvidenceRef adoptIndexed(SchedulerLease lease, EvidenceRef assignmentRef) throws IOException
	{
		RetainedTrialCall call = load(lease, assignmentRef);
		String callId = call.assignment().logicalTrialId();
		NativeCompletionPublisher.IndexedCompletion indexed = new NativeCompletionPublisher(store).
			readIndexed(lease.binding(), call.claimed(), call.preStart(), call.request().asNested());
		InitialTrialExecutionRecord record = new InitialTrialExecutionRecord(
			InitialTrialExecutionRecord.SCHEMA_VERSION, lease.binding().campaignId(), assignmentRef,
			call.preStart().producerAttemptRef(), indexed.reference());
		EvidenceRef recordRef = store.appendImmutableJson("trial-execution-records", JSON.writeValueAsBytes(record));
		MeasurementConditions afterConditions = new NativeCompletionMeasurements(store).conditions(lease.binding(),
			call.claimed(), call.preStart(), call.assignment().measurementEpochRef(), indexed.evidence());
		if (call.claimed().slot().state() != CallSlot.State.ADOPTED)
			lease.settle(callId, indexed.reference());
		lease.adopt(callId, recordRef);
		locator.openMeasurementObserver(lease.binding()).afterCall(lease, callId, afterConditions);
		return recordRef;
	}

	/**
	 * Restores the assigned trial, original claim, request, and indexed pre-start authority.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef committed trial assignment
	 * @return exact retained call evidence
	 * @throws IOException if any retained authority is missing or conflicts
	 */
	RetainedTrialCall load(SchedulerLease lease, EvidenceRef assignmentRef) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		if (!assignmentRef.collection().equals("trial-assignments"))
			throw new IllegalArgumentException("Initial trial completion requires a trial assignment");
		lease.requireLive();
		if (!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Trial completion store is not the registered campaign");
		TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
		store.requireIndexedCategoryDecision(assignment);
		if (!assignment.campaignId().equals(lease.binding().campaignId()))
			throw new IOException("Trial completion belongs to another campaign");
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), assignment.logicalTrialId());
		CallPreStartRecord preStart = store.readCallPreStartRecord(lease.binding(),
			assignment.logicalTrialId()).orElseThrow(() -> new IOException("Trial pre-start is missing"));
		byte[] requestBytes = store.readImmutableJson(claimed.slot().requestRef());
		InitialTrialRunRequest request;
		try
		{
			request = JSON.readValue(requestBytes, InitialTrialRunRequest.class);
			if (request == null || !Arrays.equals(requestBytes, JSON.writeValueAsBytes(request)))
				throw new IOException("Trial native request is noncanonical");
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Trial native request is malformed", exception);
		}
		if (!request.campaignId().equals(lease.binding().campaignId()) ||
			!request.assignmentRef().equals(assignmentRef) ||
			!request.logicalCallId().equals(assignment.logicalTrialId()) ||
			preStart.producerAttemptKind() != ProducerAttemptKind.TRIAL ||
			!preStart.requestRef().equals(claimed.slot().requestRef()))
			throw new IOException("Trial completion conflicts with its assignment or native start");
		return new RetainedTrialCall(assignment, claimed, preStart, request);
	}

	/**
	 * Original trial authority retained across scheduler owners.
	 *
	 * @param assignment committed trial selection
	 * @param claimed original scheduler claim
	 * @param preStart indexed runner identity
	 * @param request assigned native request
	 */
	record RetainedTrialCall(TrialAssignment assignment, ClaimedSlot claimed,
		CallPreStartRecord preStart, InitialTrialRunRequest request)
	{
	}
}
