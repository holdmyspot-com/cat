package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.CandidateEvidenceRepository;
import io.github.cowwoc.cat.common.optimizer.runner.PrivateGenerationContext;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Publishes the semantic request for one verified private candidate generation attempt.
 */
final class CandidateGenerationRequestService
{
	private static final String REQUESTS = "candidate-generation-requests";
	private static final String INDEX = "candidate-generation-request-index";

	private final CampaignLocator locator;
	private final CampaignStore store;
	private final CandidateEvidenceRepository evidence;
	private final GenerationAttemptWorktreeService worktrees;
	private final GenerationAttemptIntentService intents;

	/**
	 * Binds request publication to a registered campaign.
	 *
	 * @param locator registered campaign locator
	 * @param store pinned campaign store
	 */
	CandidateGenerationRequestService(CampaignLocator locator, CampaignStore store) throws IOException
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
		evidence = new CandidateEvidenceRepository(store, store.binding());
		worktrees = new GenerationAttemptWorktreeService(locator, store);
		intents = new GenerationAttemptIntentService(locator, store);
	}

	/**
	 * Publishes the request for an indexed private worktree after verifying its current state.
	 *
	 * @param readinessRef retained private worktree readiness
	 * @return indexed semantic request
	 * @throws IOException if the worktree or campaign authority changed
	 */
	EvidenceRef publish(EvidenceRef readinessRef) throws IOException
	{
		Objects.requireNonNull(readinessRef, "readinessRef");
		Campaign campaign = store.load();
		GenerationAttemptReadiness readiness = worktrees.requirePristine(readinessRef);
		GenerationAttemptIntent intent = intents.read(readiness.intentRef());
		DevelopmentRoundStart round = new DevelopmentRoundService(locator, store).read(intent.roundRef());
		String key = readinessRef.evidenceId();
		Optional<LineageEvidenceRef> prior = store.findCreateOnceIndex(INDEX, key, intent.lineage(), campaign);
		if (prior.isPresent())
		{
			EvidenceRef reference = prior.orElseThrow().evidence();
			requireMatchingReadiness(reference, readinessRef);
			worktrees.requirePristine(readinessRef);
			return reference;
		}

		CandidateGenerationRequest request = new CandidateGenerationRequest(CandidateGenerationRequest.SCHEMA_VERSION,
			intent.campaignId(), intent.lineage(), intent.logicalCallId(), intent.categoryId(),
			intent.incumbentCandidateId(), intent.roundRef(), round.sourceRevisionRef(),
			intent.measurementEpochRef(), intent.confirmedRootsRef(), readinessRef, intent.selectedProject(),
			intent.privateProject());
		EvidenceRef reference = evidence.publish(REQUESTS, request);
		try
		{
			store.appendCreateOnceIndexIfCurrent(INDEX, key, new LineageEvidenceRef(intent.lineage(), reference),
				campaign);
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex(INDEX, key, intent.lineage());
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		requireMatchingReadiness(reference, readinessRef);
		worktrees.requirePristine(readinessRef);
		return reference;
	}

	/**
	 * Rejects an index that points at another verified private worktree.
	 *
	 * @param requestRef indexed request to check
	 * @param readinessRef private worktree selected by the caller
	 * @throws IOException if the indexed request belongs to another readiness
	 */
	private void requireMatchingReadiness(EvidenceRef requestRef, EvidenceRef readinessRef) throws IOException
	{
		if (!read(requestRef).readinessRef().equals(readinessRef))
			throw new IOException("Candidate generation request belongs to another readiness");
	}

	/**
	 * Reopens an indexed request and verifies its round, intent, and private-worktree binding.
	 *
	 * @param reference immutable semantic request
	 * @return source-backed request
	 * @throws IOException if its evidence or index is missing or inconsistent
	 */
	CandidateGenerationRequest read(EvidenceRef reference) throws IOException
	{
		CandidateGenerationRequest request = evidence.read(reference, REQUESTS,
			CandidateGenerationRequest.class);
		GenerationAttemptReadiness readiness = worktrees.read(request.readinessRef());
		GenerationAttemptIntent intent = intents.read(readiness.intentRef());
		DevelopmentRoundStart round = new DevelopmentRoundService(locator, store).read(intent.roundRef());
		if (!request.campaignId().equals(intent.campaignId()) ||
			!request.lineage().equals(intent.lineage()) ||
			!request.logicalCallId().equals(intent.logicalCallId()) ||
			!request.categoryId().equals(intent.categoryId()) ||
			!request.incumbentCandidateId().equals(intent.incumbentCandidateId()) ||
			!request.roundRef().equals(intent.roundRef()) ||
			!request.sourceRevisionRef().equals(round.sourceRevisionRef()) ||
			!request.measurementEpochRef().equals(intent.measurementEpochRef()) ||
			!request.confirmedRootsRef().equals(intent.confirmedRootsRef()) ||
			!request.selectedProject().equals(intent.selectedProject()) ||
			!request.privateProject().equals(intent.privateProject()) ||
			!store.readCreateOnceIndex(INDEX, request.readinessRef().evidenceId(), intent.lineage()).
				evidence().equals(reference))
			throw new IOException("Candidate generation request conflicts with its sources or index");
		return request;
	}

	/**
	 * Revalidates an indexed request and its private worktree for native authoring.
	 *
	 * @param requestRef indexed semantic request
	 * @return native context bound to the verified private worktree
	 * @throws IOException if retained authority or current worktree state is invalid
	 */
	PrivateGenerationContext context(EvidenceRef requestRef) throws IOException
	{
		CandidateGenerationRequest request = read(requestRef);
		worktrees.requirePristine(request.readinessRef());
		return new PrivateGenerationContext(request.selectedProject(), request.privateProject(), requestRef,
			request.readinessRef(), request.confirmedRootsRef());
	}
}
