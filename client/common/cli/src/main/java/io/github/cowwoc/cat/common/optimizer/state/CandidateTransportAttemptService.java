package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes one claimed candidate generation attempt and its native pre-start identity.
 */
final class CandidateTransportAttemptService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int FIRST_ATTEMPT_ORDINAL = 0;

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds the attempt producer to a registered campaign.
	 *
	 * @param locator registered campaign locator
	 * @param store pinned campaign store
	 */
	CandidateTransportAttemptService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes the first indexed attempt for an admitted private generation call.
	 *
	 * @param lease current scheduler owner
	 * @param semanticRequestRef indexed generation request
	 * @return immutable indexed attempt
	 * @throws IOException if its request, private worktree, or claim changed
	 */
	EvidenceRef publishAttempt(SchedulerLease lease, EvidenceRef semanticRequestRef) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		requireRegisteredStore(lease);
		CandidateGenerationRunRequestService nativeRequests = new CandidateGenerationRunRequestService(locator,
			store);
		EvidenceRef nativeRequestRef = nativeRequests.prepare(semanticRequestRef);
		CandidateGenerationRequest semantic = new CandidateGenerationRequestService(locator, store).
			read(semanticRequestRef);
		return publishAttempt(lease, semanticRequestRef, nativeRequestRef, semantic);
	}

	/**
	 * Publishes an attempt using source authority validated in this operation.
	 *
	 * @param lease current scheduler owner
	 * @param semanticRequestRef exact immutable semantic request
	 * @param nativeRequestRef native request selected by that authority
	 * @param semantic freshly source-validated semantic request
	 * @return immutable indexed attempt
	 * @throws IOException if the current claim conflicts with the supplied request
	 */
	private EvidenceRef publishAttempt(SchedulerLease lease, EvidenceRef semanticRequestRef,
		EvidenceRef nativeRequestRef, CandidateGenerationRequest semantic) throws IOException
	{
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), semantic.logicalCallId());
		if (claimed.slot().state() != CallSlot.State.CLAIMED ||
			claimed.slot().fence() != lease.fence() ||
			!claimed.slot().requestRef().equals(nativeRequestRef) ||
			!claimed.claim().preparationOwnerRequestRef().isEmpty() ||
			!claimed.claim().preparationCycleRef().isEmpty())
			throw new IOException("Candidate attempt requires the admitted unstarted scheduler claim");
		PreparedLineageCall owner = new PreparedLineageCall(1, ProducerAttemptKind.CANDIDATE,
			lease.binding().campaignId(), semantic.lineage(), semantic.logicalCallId(), semanticRequestRef,
			nativeRequestRef);
		EvidenceRef ownerRef = store.appendImmutableJson("prepared-lineage-calls", JSON.writeValueAsBytes(owner));
		ProducerAttemptHeader header = new ProducerAttemptHeader(ProducerAttemptKind.CANDIDATE,
			semantic.logicalCallId(), nativeRequestRef, claimed.slotClaimRef(), ownerRef, Optional.empty());
		CandidateTransportAttempt attempt = new CandidateTransportAttempt(header, semanticRequestRef,
			FIRST_ATTEMPT_ORDINAL);
		EvidenceRef attemptRef = store.appendImmutableJson("candidate-native-attempts",
			JSON.writeValueAsBytes(attempt));
		String key = OwnerAttemptIndexKey.deriveLineage(ProducerAttemptKind.CANDIDATE, ownerRef,
			nativeRequestRef, claimed.slotClaimRef(), FIRST_ATTEMPT_ORDINAL);
		store.appendCreateOnceIndex("candidate-transport-attempts", key,
			new LineageEvidenceRef(semantic.lineage(), attemptRef));
		return attemptRef;
	}

	/**
	 * Publishes the native pre-start record for the exact indexed candidate attempt.
	 *
	 * @param lease current scheduler owner
	 * @param attemptRef indexed candidate attempt
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return indexed pre-start record
	 * @throws IOException if retained source, claim, attempt, or private worktree conflicts
	 */
	EvidenceRef publishPreStart(SchedulerLease lease, EvidenceRef attemptRef,
		HarnessRunManager manager, Path artifact) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(attemptRef, "attemptRef");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");
		requireRegisteredStore(lease);
		CandidateTransportAttempt attempt = readAttempt(attemptRef);
		ProducerAttemptHeader header = attempt.header();
		CandidateGenerationRunRequest nativeRequest = new CandidateGenerationRunRequestService(locator,
			store).read(header.runRequestRef());
		new GenerationAttemptWorktreeService(locator, store).
			requirePristine(nativeRequest.context().readinessRef());
		CandidateGenerationRequest semantic = new CandidateGenerationRequestService(locator, store).
			read(attempt.requestRef());
		return publishPreStart(lease, attemptRef, attempt, new PreparedRequest(attempt.requestRef(),
			semantic, header.runRequestRef(), nativeRequest), manager, artifact);
	}

	/**
	 * Publishes pre-start authority from a canonical attempt and source authority validated in this operation.
	 *
	 * @param lease current scheduler owner
	 * @param attemptRef indexed attempt identity
	 * @param attempt canonical retained attempt
	 * @param prepared freshly validated request and semantic authority
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return indexed pre-start record
	 * @throws IOException if current claim, owner, attempt, or index conflicts
	 */
	private EvidenceRef publishPreStart(SchedulerLease lease, EvidenceRef attemptRef,
		CandidateTransportAttempt attempt, PreparedRequest prepared, HarnessRunManager manager, Path artifact)
		throws IOException
	{
		ProducerAttemptHeader header = attempt.header();
		CandidateGenerationRunRequest nativeRequest = prepared.request();
		CandidateGenerationRequest semantic = prepared.semantic();
		ClaimedSlot claimed = store.readCallSlotClaim(lease.binding(), semantic.logicalCallId());
		if (claimed.slot().state() != CallSlot.State.CLAIMED ||
			claimed.slot().fence() != lease.fence() ||
			!nativeRequest.semanticRequestRef().equals(attempt.requestRef()) ||
			!attempt.requestRef().equals(prepared.semanticRequestRef()) ||
			!header.runRequestRef().equals(prepared.requestRef()) ||
			!header.logicalCallId().equals(semantic.logicalCallId()) ||
			!header.slotClaimRef().equals(claimed.slotClaimRef()) ||
			!claimed.slot().requestRef().equals(header.runRequestRef()))
			throw new IOException("Candidate attempt conflicts with its scheduler claim");
		PreparedLineageCall owner = new PreparedLineageCall(1, ProducerAttemptKind.CANDIDATE,
			lease.binding().campaignId(), semantic.lineage(), semantic.logicalCallId(), attempt.requestRef(),
			header.runRequestRef());
		if (!Arrays.equals(store.readImmutableJson(header.ownerRequestRef()), JSON.writeValueAsBytes(owner)))
			throw new IOException("Candidate attempt owner differs from its semantic request");
		String key = OwnerAttemptIndexKey.deriveLineage(ProducerAttemptKind.CANDIDATE,
			header.ownerRequestRef(), header.runRequestRef(), claimed.slotClaimRef(),
			attempt.attemptOrdinal());
		LineageEvidenceRef indexed = store.readCreateOnceIndex("candidate-transport-attempts", key,
			semantic.lineage());
		if (!indexed.evidence().equals(attemptRef))
			throw new IOException("Candidate attempt index identifies another attempt");
		return CallPreStartPublisher.publish(store, lease.binding(), semantic.logicalCallId(),
			ProducerAttemptKind.CANDIDATE, attemptRef, header.ownerRequestRef(), manager, artifact);
	}

	/**
	 * Publishes an admitted generation attempt and pre-start identity from one fresh request preparation.
	 * Independent publication calls still reopen their authority. The scheduler subsequently rechecks start authority.
	 *
	 * @param lease current scheduler owner
	 * @param semanticRequestRef indexed private generation request
	 * @param manager native run identity source
	 * @param artifact selected packaged runner
	 * @return native request and indexed attempt requiring subsequent start authorization
	 * @throws IOException if source authority, workspace, current claim, or retained indexes conflict
	 */
	PreparedStart prepareStart(SchedulerLease lease, EvidenceRef semanticRequestRef,
		HarnessRunManager manager, Path artifact) throws IOException
	{
		Objects.requireNonNull(lease, "lease");
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		Objects.requireNonNull(manager, "manager");
		Objects.requireNonNull(artifact, "artifact");
		requireRegisteredStore(lease);
		CandidateGenerationRunRequestService requests = new CandidateGenerationRunRequestService(locator, store);
		EvidenceRef requestRef = requests.prepare(semanticRequestRef);
		CandidateGenerationRequest semantic = new CandidateGenerationRequestService(locator, store).
			read(semanticRequestRef);
		CandidateGenerationRunRequest request = requests.readWithSemantic(requestRef, semantic);
		PreparedRequest prepared = new PreparedRequest(semanticRequestRef, semantic, requestRef, request);
		EvidenceRef attemptRef = publishAttempt(lease, semanticRequestRef, requestRef, semantic);
		requireRegisteredStore(lease);
		new GenerationAttemptWorktreeService(locator, store).requirePristine(request.context().readinessRef());
		publishPreStart(lease, attemptRef, readAttempt(attemptRef), prepared, manager, artifact);
		return new PreparedStart(attemptRef, request);
	}

	/**
	 * Source authority retained only within one preparation operation.
	 *
	 * @param semanticRequestRef exact semantic request identity
	 * @param semantic freshly source-validated semantic request
	 * @param requestRef exact native request identity
	 * @param request native request checked against that semantic authority and remaining sources
	 */
	private record PreparedRequest(EvidenceRef semanticRequestRef, CandidateGenerationRequest semantic,
		EvidenceRef requestRef, CandidateGenerationRunRequest request)
	{
	}

	/**
	 * Generation preparation that still requires current scheduler start authorization and cancellation checks.
	 *
	 * @param attemptRef indexed canonical attempt
	 * @param request source-validated native request
	 */
	record PreparedStart(EvidenceRef attemptRef, CandidateGenerationRunRequest request)
	{
	}

	/**
	 * Restores a canonical candidate transport attempt.
	 *
	 * @param reference retained attempt evidence
	 * @return typed candidate attempt
	 * @throws IOException if the attempt is missing or malformed
	 */
	CandidateTransportAttempt readAttempt(EvidenceRef reference) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		if (!reference.collection().equals("candidate-native-attempts"))
			throw new IOException("Candidate attempt has the wrong evidence collection");
		byte[] bytes = store.readImmutableJson(reference);
		try
		{
			CandidateTransportAttempt attempt = JSON.readValue(bytes, CandidateTransportAttempt.class);
			if (attempt == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(attempt)))
				throw new IOException("Candidate attempt is noncanonical");
			return attempt;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Candidate attempt is malformed", exception);
		}
	}

	/**
	 * Requires the live scheduler owner and registered campaign store.
	 *
	 * @param lease current scheduler owner
	 * @throws IOException if the store or binding differs
	 */
	private void requireRegisteredStore(SchedulerLease lease) throws IOException
	{
		lease.requireLive();
		if (!locator.requireRegisteredBinding(lease.binding()).directory().equals(store.directory()))
			throw new IOException("Candidate attempt store is not the registered campaign");
		store.lockedSnapshot(lease.binding());
	}
}
