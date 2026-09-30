package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Publishes a claimed initial trial's transport attempt before native dispatch. */
public final class TrialTransportAttemptService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int FIRST_ATTEMPT_ORDINAL = 0;

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds the producer to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public TrialTransportAttemptService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes the first indexed attempt for an admitted trial.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef assigned trial
	 * @return indexed attempt
	 * @throws IOException if the claim or assignment is invalid
	 */
	public EvidenceRef publishAttempt(SchedulerLease lease, EvidenceRef assignmentRef) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		requireRegisteredStore(lease);
		EvidenceRef requestRef = new InitialTrialRequestService(locator, store).prepare(assignmentRef);
		TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
		return publishAttempt(lease, new PreparedRequest(assignmentRef, requestRef, assignment));
	}

	/**
	 * Publishes an attempt using trial authority freshly validated in this operation.
	 *
	 * @param lease current scheduler owner
	 * @param prepared current assigned trial and exact native request identity
	 * @return indexed trial attempt
	 * @throws IOException if the category decision or admitted claim conflicts
	 */
	private EvidenceRef publishAttempt(SchedulerLease lease, PreparedRequest prepared) throws IOException
	{
		EvidenceRef assignmentRef = prepared.assignmentRef();
		EvidenceRef requestRef = prepared.requestRef();
		TrialAssignment assignment = prepared.assignment();
		store.requireIndexedCategoryDecision(assignment);
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), assignment.logicalTrialId());
		if (claimed.slot().state() != CallSlot.State.CLAIMED ||
			!claimed.slot().requestRef().equals(requestRef) ||
			!claimed.claim().preparationOwnerRequestRef().isEmpty() ||
			!claimed.claim().preparationCycleRef().isEmpty())
			throw new IOException("Trial attempt requires the assigned, unstarted scheduler claim");
		PreparedLineageCall owner = new PreparedLineageCall(1, ProducerAttemptKind.TRIAL,
			lease.binding().campaignId(), assignment.lineage(), assignment.logicalTrialId(),
			assignmentRef, requestRef);
		EvidenceRef ownerRef = store.appendImmutableJson("prepared-lineage-calls", JSON.writeValueAsBytes(owner));
		ProducerAttemptHeader header = new ProducerAttemptHeader(ProducerAttemptKind.TRIAL,
			assignment.logicalTrialId(), requestRef, claimed.slotClaimRef(), ownerRef, Optional.empty());
		TrialTransportAttempt attempt = new TrialTransportAttempt(header, assignmentRef,
			FIRST_ATTEMPT_ORDINAL);
		EvidenceRef attemptRef = store.appendImmutableJson("trial-transport-attempts",
			JSON.writeValueAsBytes(attempt));
		String key = OwnerAttemptIndexKey.deriveLineage(ProducerAttemptKind.TRIAL, ownerRef,
			requestRef, claimed.slotClaimRef(), FIRST_ATTEMPT_ORDINAL);
		store.appendCreateOnceIndex("trial-execution-attempts", key,
			new LineageEvidenceRef(assignment.lineage(), attemptRef));
		return attemptRef;
	}

	/**
	 * Publishes the retained pre-start identity for an indexed trial attempt.
	 *
	 * @param lease current scheduler owner
	 * @param attemptRef indexed trial attempt
	 * @param manager native run identity source
	 * @param artifact packaged runner selected for dispatch
	 * @return indexed pre-start evidence
	 * @throws IOException if any retained authority conflicts
	 */
	public EvidenceRef publishPreStart(SchedulerLease lease, EvidenceRef attemptRef,
		HarnessRunManager manager, Path artifact) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(attemptRef, "attemptRef");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");
		requireRegisteredStore(lease);
		TrialTransportAttempt attempt = readAttempt(attemptRef);
		TrialAssignment assignment = store.readTrialAssignment(attempt.requestRef());
		EvidenceRef expectedRequest = new InitialTrialRequestService(locator, store).
			prepare(attempt.requestRef());
		return publishPreStart(lease, attemptRef, attempt,
			new PreparedRequest(attempt.requestRef(), expectedRequest, assignment), manager, artifact);
	}

	/**
	 * Publishes pre-start authority using the request freshly validated in this operation.
	 *
	 * @param lease current scheduler owner
	 * @param attemptRef indexed trial attempt
	 * @param attempt canonical retained attempt
	 * @param prepared current assigned trial and exact native request identity
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return indexed pre-start record
	 * @throws IOException if the attempt, claim, or index conflicts
	 */
	private EvidenceRef publishPreStart(SchedulerLease lease, EvidenceRef attemptRef,
		TrialTransportAttempt attempt, PreparedRequest prepared, HarnessRunManager manager, Path artifact)
		throws IOException
	{
		ProducerAttemptHeader header = attempt.header();
		TrialAssignment assignment = prepared.assignment();
		EvidenceRef expectedRequest = prepared.requestRef();
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), assignment.logicalTrialId());
		if (claimed.slot().state() != CallSlot.State.CLAIMED ||
			!header.logicalCallId().equals(assignment.logicalTrialId()) ||
			!header.runRequestRef().equals(expectedRequest) ||
			!header.slotClaimRef().equals(claimed.slotClaimRef()) ||
			!claimed.slot().requestRef().equals(expectedRequest))
			throw new IOException("Trial attempt conflicts with its assigned scheduler claim");
		String key = OwnerAttemptIndexKey.deriveLineage(ProducerAttemptKind.TRIAL,
			header.ownerRequestRef(), expectedRequest, claimed.slotClaimRef(), attempt.attemptOrdinal());
		LineageEvidenceRef indexed = store.readCreateOnceIndex("trial-execution-attempts", key,
			assignment.lineage());
		if (!indexed.evidence().equals(attemptRef))
			throw new IOException("Trial attempt index identifies another attempt");
		return CallPreStartPublisher.publish(store, lease.binding(), assignment.logicalTrialId(),
			ProducerAttemptKind.TRIAL, attemptRef, header.ownerRequestRef(), manager, artifact);
	}

	/**
	 * Publishes an admitted trial's attempt and pre-start record using one fresh request preparation.
	 * The caller must still authorize start and recheck the selected worktree before native dispatch.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef assigned trial
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return exact native request and indexed attempt identities
	 * @throws IOException if source authority, claim, or retained indexes conflict
	 */
	PreparedStart prepareStart(SchedulerLease lease, EvidenceRef assignmentRef,
		HarnessRunManager manager, Path artifact) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");
		requireRegisteredStore(lease);
		TrialAssignment assignment = store.readTrialAssignment(assignmentRef);
		EvidenceRef requestRef = new InitialTrialRequestService(locator, store).prepare(assignmentRef);
		PreparedRequest prepared = new PreparedRequest(assignmentRef, requestRef, assignment);
		EvidenceRef attemptRef = publishAttempt(lease, prepared);
		requireRegisteredStore(lease);
		publishPreStart(lease, attemptRef, readAttempt(attemptRef), prepared, manager, artifact);
		return new PreparedStart(requestRef, attemptRef);
	}

	/**
	 * Rehashes and reads the exact canonical transport attempt.
	 *
	 * @param attemptRef retained trial attempt
	 * @return canonical attempt requiring claim and index verification
	 * @throws IOException if the collection, bytes, or shape is invalid
	 */
	private TrialTransportAttempt readAttempt(EvidenceRef attemptRef) throws IOException
	{
		if (!attemptRef.collection().equals("trial-transport-attempts"))
			throw new IOException("Trial attempt has the wrong evidence collection");
		byte[] bytes = store.readImmutableJson(attemptRef);
		try
		{
			TrialTransportAttempt attempt = JSON.readValue(bytes, TrialTransportAttempt.class);
			if (attempt == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(attempt)))
				throw new IOException("Trial attempt is noncanonical");
			return attempt;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Trial attempt is malformed", exception);
		}
	}

	/**
	 * Assigned authority validated for one preparation operation.
	 *
	 * @param assignmentRef immutable assigned trial
	 * @param requestRef exact native request identity
	 * @param assignment canonical trial assignment
	 */
	private record PreparedRequest(EvidenceRef assignmentRef, EvidenceRef requestRef, TrialAssignment assignment)
	{
	}

	/**
	 * Published trial preparation that still requires start, cancellation, and selected-worktree checks.
	 *
	 * @param requestRef exact native request identity
	 * @param attemptRef indexed trial attempt
	 */
	record PreparedStart(EvidenceRef requestRef, EvidenceRef attemptRef)
	{
	}

	/**
	 * Requires the current scheduler owner and the registered campaign store.
	 *
	 * @param lease current scheduler owner
	 * @throws IOException if the store or binding differs
	 */
	private void requireRegisteredStore(SchedulerLease lease) throws IOException
	{
		lease.requireLive();
		CampaignStore registered = locator.requireRegisteredBinding(lease.binding());
		if (!registered.directory().equals(store.directory()))
			throw new IOException("Trial attempt store is not the registered campaign");
		store.lockedSnapshot(lease.binding());
	}
}
