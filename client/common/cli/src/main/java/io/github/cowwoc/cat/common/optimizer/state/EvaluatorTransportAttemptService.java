package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Publishes a claimed evaluator's indexed attempt and native pre-start authority. */
public final class EvaluatorTransportAttemptService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int FIRST_ATTEMPT_ORDINAL = 0;

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds the attempt producer to a registered campaign.
	 *
	 * @param locator owning campaign locator
	 * @param store pinned campaign store
	 */
	public EvaluatorTransportAttemptService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes the first indexed attempt for an admitted evaluator.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @return indexed evaluator attempt
	 * @throws IOException if the claim or assignment is invalid
	 */
	public EvidenceRef publishAttempt(SchedulerLease lease, EvidenceRef assignmentRef) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		requireRegisteredStore(lease);
		return publishAttempt(lease, loadRequest(assignmentRef));
	}

	/**
	 * Publishes an attempt using evaluator authority validated in the current operation.
	 *
	 * @param lease current scheduler owner
	 * @param prepared freshly validated evaluator request and semantic authority
	 * @return indexed evaluator attempt
	 * @throws IOException if the admitted claim conflicts with the request
	 */
	private EvidenceRef publishAttempt(SchedulerLease lease, PreparedRequest prepared) throws IOException
	{
		EvidenceRef requestRef = prepared.requestRef();
		InitialEvaluatorRunRequest request = prepared.request();
		LineageKey lineage = prepared.semantic().lineage();
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), request.logicalCallId());
		if (claimed.slot().state() != CallSlot.State.CLAIMED ||
			!claimed.slot().requestRef().equals(requestRef) ||
			!claimed.claim().preparationOwnerRequestRef().isEmpty() ||
			!claimed.claim().preparationCycleRef().isEmpty())
			throw new IOException("Evaluator attempt requires the admitted unstarted scheduler claim");
		PreparedLineageCall owner = new PreparedLineageCall(1, ProducerAttemptKind.EVALUATOR,
			lease.binding().campaignId(), lineage, request.logicalCallId(),
			request.semanticRequestRef(), requestRef);
		EvidenceRef ownerRef = store.appendImmutableJson("prepared-lineage-calls", JSON.writeValueAsBytes(owner));
		ProducerAttemptHeader header = new ProducerAttemptHeader(ProducerAttemptKind.EVALUATOR,
			request.logicalCallId(), requestRef, claimed.slotClaimRef(), ownerRef, Optional.empty());
		EvaluatorTransportAttempt attempt = new EvaluatorTransportAttempt(header,
			request.semanticRequestRef(), FIRST_ATTEMPT_ORDINAL);
		EvidenceRef attemptRef = store.appendImmutableJson("evaluator-transport-attempts",
			JSON.writeValueAsBytes(attempt));
		String key = OwnerAttemptIndexKey.deriveLineage(ProducerAttemptKind.EVALUATOR, ownerRef,
			requestRef, claimed.slotClaimRef(), FIRST_ATTEMPT_ORDINAL);
		store.appendCreateOnceIndex("evaluator-execution-attempts", key,
			new LineageEvidenceRef(lineage, attemptRef));
		return attemptRef;
	}

	/**
	 * Publishes the retained native pre-start identity for an indexed evaluator attempt.
	 *
	 * @param lease current scheduler owner
	 * @param attemptRef indexed evaluator attempt
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return indexed pre-start record
	 * @throws IOException if retained authority conflicts
	 */
	public EvidenceRef publishPreStart(SchedulerLease lease, EvidenceRef attemptRef,
		HarnessRunManager manager, Path artifact) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(attemptRef, "attemptRef");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");
		requireRegisteredStore(lease);
		EvaluatorTransportAttempt attempt = readAttempt(attemptRef);
		InitialEvaluatorRequestService requests = new InitialEvaluatorRequestService(locator, store);
		InitialEvaluatorRequest semantic = requests.readSemanticRequest(attempt.requestRef());
		return publishPreStart(lease, attemptRef, attempt, loadRequest(semantic.assignmentRef()), manager, artifact);
	}

	/**
	 * Publishes pre-start authority from an indexed attempt and authority validated in this operation.
	 *
	 * @param lease current scheduler owner
	 * @param attemptRef indexed evaluator attempt
	 * @param attempt canonical retained attempt
	 * @param prepared freshly validated evaluator request and semantic authority
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return indexed pre-start record
	 * @throws IOException if the attempt, index, or current claim conflicts
	 */
	private EvidenceRef publishPreStart(SchedulerLease lease, EvidenceRef attemptRef,
		EvaluatorTransportAttempt attempt, PreparedRequest prepared, HarnessRunManager manager, Path artifact)
		throws IOException
	{
		EvidenceRef expectedRequest = prepared.requestRef();
		InitialEvaluatorRunRequest nativeRequest = prepared.request();
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), nativeRequest.logicalCallId());
		ProducerAttemptHeader header = attempt.header();
		if (claimed.slot().state() != CallSlot.State.CLAIMED ||
			!nativeRequest.semanticRequestRef().equals(attempt.requestRef()) ||
			!header.logicalCallId().equals(nativeRequest.logicalCallId()) ||
			!header.runRequestRef().equals(expectedRequest) ||
			!header.slotClaimRef().equals(claimed.slotClaimRef()) ||
			!claimed.slot().requestRef().equals(expectedRequest))
			throw new IOException("Evaluator attempt conflicts with its scheduler claim");
		String key = OwnerAttemptIndexKey.deriveLineage(ProducerAttemptKind.EVALUATOR,
			header.ownerRequestRef(), expectedRequest, claimed.slotClaimRef(), attempt.attemptOrdinal());
		LineageEvidenceRef indexed = store.readCreateOnceIndex("evaluator-execution-attempts", key,
			prepared.semantic().lineage());
		if (!indexed.evidence().equals(attemptRef))
			throw new IOException("Evaluator attempt index identifies another attempt");
		return CallPreStartPublisher.publish(store, lease.binding(), nativeRequest.logicalCallId(),
			ProducerAttemptKind.EVALUATOR, attemptRef, header.ownerRequestRef(), manager, artifact);
	}

	/**
	 * Publishes an admitted evaluator's attempt and pre-start record from one fresh request preparation.
	 * Independent publication calls still reopen their authority separately.
	 *
	 * @param lease current scheduler owner
	 * @param assignmentRef adopted trial assignment
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return request and attempt for the caller's subsequent start authorization
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
		PreparedRequest prepared = loadRequest(assignmentRef);
		EvidenceRef attemptRef = publishAttempt(lease, prepared);
		requireRegisteredStore(lease);
		publishPreStart(lease, attemptRef, readAttempt(attemptRef), prepared, manager, artifact);
		return new PreparedStart(attemptRef, prepared.request());
	}

	/**
	 * Validates the adopted trial and confirmed evaluator authority, then reads its exact published requests.
	 *
	 * @param assignmentRef adopted trial assignment
	 * @return evaluator authority usable only in this operation
	 * @throws IOException if source authority or immutable request bytes are invalid
	 */
	private PreparedRequest loadRequest(EvidenceRef assignmentRef) throws IOException
	{
		InitialEvaluatorRequestService requests = new InitialEvaluatorRequestService(locator, store);
		EvidenceRef requestRef = requests.prepare(assignmentRef);
		InitialEvaluatorRunRequest request = requests.readRunRequest(requestRef);
		return new PreparedRequest(requestRef, request, requests.readSemanticRequest(request.semanticRequestRef()));
	}

	/**
	 * Immutable evaluator authority freshly validated for one preparation operation.
	 *
	 * @param requestRef published native request identity
	 * @param request exact native request
	 * @param semantic exact semantic evaluator authority
	 */
	private record PreparedRequest(EvidenceRef requestRef, InitialEvaluatorRunRequest request,
		InitialEvaluatorRequest semantic)
	{
	}

	/**
	 * Published evaluator preparation that still requires the caller's start and cancellation checks.
	 *
	 * @param attemptRef indexed evaluator attempt
	 * @param request source-validated native request
	 */
	record PreparedStart(EvidenceRef attemptRef, InitialEvaluatorRunRequest request)
	{
	}

	/**
	 * Restores the canonical evaluator transport attempt.
	 *
	 * @param reference retained attempt evidence
	 * @return typed evaluator attempt
	 * @throws IOException if the attempt is missing or malformed
	 */
	private EvaluatorTransportAttempt readAttempt(EvidenceRef reference) throws IOException
	{
		if (!reference.collection().equals("evaluator-transport-attempts"))
			throw new IOException("Evaluator attempt has the wrong evidence collection");
		byte[] bytes = store.readImmutableJson(reference);
		try
		{
			EvaluatorTransportAttempt attempt = JSON.readValue(bytes, EvaluatorTransportAttempt.class);
			if (attempt == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(attempt)))
				throw new IOException("Evaluator attempt is noncanonical");
			return attempt;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Evaluator attempt is malformed", exception);
		}
	}

	/**
	 * Requires the current scheduler owner and registered campaign store.
	 *
	 * @param lease current scheduler owner
	 * @throws IOException if the store or binding differs
	 */
	private void requireRegisteredStore(SchedulerLease lease) throws IOException
	{
		lease.requireLive();
		if (!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Evaluator attempt store is not the registered campaign");
		store.lockedSnapshot(lease.binding());
	}
}
