package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentEvidenceDecision;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retains and verifies a category's pending development evidence review.
 */
final class DevelopmentReviewService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String REQUESTS = "development-review-requests";
	private static final String INDEX = "development-review-index";
	private static final String RESPONSES = "development-review-responses";
	private static final String RESPONSE_INDEX = "development-review-response-index";

	private final CampaignStore store;

	/**
	 * Binds review publication to one campaign evidence store.
	 *
	 * @param store owning campaign store
	 */
	DevelopmentReviewService(CampaignStore store)
	{
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Retains any reached review request and refuses a new representative draw until it is answered.
	 *
	 * @param evidence current candidate's source-backed representative outcomes
	 * @param contract confirmed readiness and review policy
	 * @throws IOException if a review is pending or a choice stopped sampling
	 */
	void retainDueAndRequireEligible(DevelopmentEvidenceService.Snapshot evidence,
		ConfirmedContract contract) throws IOException
	{
		Optional<DueReview> due = due(evidence, contract);
		if (due.isEmpty())
			return;
		DueReview review = due.orElseThrow();
		EvidenceRef requestRef = publishPending(evidence, contract, review.categoryId(),
			review.reviewPoint(), review.precedingResponseRef());
		throw new IOException("Development evidence review is required before another draw: " +
			requestRef.evidenceId());
	}

	/**
	 * Refuses a draw under the campaign state lock without publishing an index there.
	 *
	 * @param evidence current candidate's source-backed representative outcomes
	 * @param contract confirmed readiness and review policy
	 * @throws IOException if a review is due or a choice stopped sampling
	 */
	void requireEligible(DevelopmentEvidenceService.Snapshot evidence, ConfirmedContract contract)
		throws IOException
	{
		if (due(evidence, contract).isPresent())
			throw new IOException("Development evidence review is required before another draw");
	}

	/**
	 * Finds the first category whose counted outcomes reached an unanswered checkpoint.
	 *
	 * @param evidence current candidate's representative outcomes
	 * @param contract confirmed category and review policy
	 * @return checkpoint requiring a retained user choice
	 * @throws IOException if a prior choice ended representative sampling
	 */
	private Optional<DueReview> due(DevelopmentEvidenceService.Snapshot evidence,
		ConfirmedContract contract) throws IOException
	{
		Objects.requireNonNull(evidence, "evidence");
		Objects.requireNonNull(contract, "contract");
		Campaign campaign = evidence.campaign();
		LineageSummary active = campaign.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow(() ->
			new IOException("Development review has no active confirmed lineage"));
		LineageKey lineage = new LineageKey(active.lineageId(), active.confirmedContractId());
		Map<String, DevelopmentEvidenceService.CategorySeries> series = evidence.categorySeries(
			contract.developmentAllocationPolicy().batchSize());
		for (Category category : contract.categories())
		{
			DevelopmentEvidenceService.CategorySeries history = series.get(category.id());
			if (history == null)
				continue;
			DevelopmentEvidenceDecision.Status status = DevelopmentEvidenceDecision.classify(category.id(),
				new DevelopmentEvidenceDecision.CategoryEvidence(history.total(),
					history.completedBatches(), false, false), contract.developmentReadiness(),
				contract.developmentAllocationPolicy());
			if (status != DevelopmentEvidenceDecision.Status.REVIEW_REQUIRED)
				continue;
			ReviewCheckpoint checkpoint = resolveCheckpoint(history.total().trials(),
				contract.developmentReadiness().reviewCheckpoint(), point ->
					responseFor(campaign, lineage, campaign.currentCandidateId(),
						campaign.measurementState().epochRef(), category.id(), point));
			if (checkpoint.ended())
				throw new IOException("Development review choice ended representative sampling: " + category.id());
			if (checkpoint.authorizationRef().isEmpty())
				return Optional.of(new DueReview(category.id(), checkpoint.reviewPoint(),
					checkpoint.precedingResponseRef()));
		}
		return Optional.empty();
	}

	/**
	 * Advances one category through answered review checkpoints using its valid outcome count.
	 *
	 * @param outcomes valid representative outcomes in the category
	 * @param firstPoint the confirmed first review checkpoint
	 * @param lookup reads a validated response at a reached checkpoint
	 * @return the due checkpoint, current authorization, or terminal review choice
	 * @throws IOException if response evidence is invalid or cannot be read
	 */
	static ReviewCheckpoint resolveCheckpoint(int outcomes, int firstPoint, ResponseLookup lookup) throws IOException
	{
		if (outcomes < 0 || firstPoint <= 0)
			throw new IllegalArgumentException("Outcome count and first review checkpoint are invalid");
		Objects.requireNonNull(lookup, "lookup");
		int point = firstPoint;
		Optional<EvidenceRef> preceding = Optional.empty();
		while (true)
		{
			Optional<RetainedResponse> retained = lookup.find(point);
			if (retained.isEmpty())
				return new ReviewCheckpoint(point, preceding, Optional.empty(), false);
			RetainedResponse answered = retained.orElseThrow();
			DevelopmentReviewResponse response = answered.response();
			if (response.choice() != DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE)
				return new ReviewCheckpoint(point, preceding, Optional.empty(), true);
			if (response.nextReviewPoint() <= point)
				throw new IOException("Development review response does not advance its checkpoint: " + point);
			if (outcomes < response.nextReviewPoint())
				return new ReviewCheckpoint(point, preceding, Optional.of(answered.reference()), false);
			preceding = Optional.of(answered.reference());
			point = response.nextReviewPoint();
		}
	}

	/**
	 * Reads a response already validated against its retained request.
	 */
	@FunctionalInterface
	interface ResponseLookup
	{
		/**
		 * Finds the response to one checkpoint, if answered.
		 *
		 * @param reviewPoint the reached checkpoint
		 * @return retained response, or empty when a user choice is due
		 * @throws IOException if retained response evidence is invalid
		 */
		Optional<RetainedResponse> find(int reviewPoint) throws IOException;
	}

	/**
	 * One validated response and its immutable evidence reference.
	 *
	 * @param reference retained response identity
	 * @param response validated user choice
	 */
	record RetainedResponse(EvidenceRef reference, DevelopmentReviewResponse response)
	{
	}

	/**
	 * Current review position after considering every answered checkpoint.
	 *
	 * @param reviewPoint the current checkpoint
	 * @param precedingResponseRef response that established the current checkpoint, if any
	 * @param authorizationRef response permitting more outcomes before the next checkpoint, if any
	 * @param ended whether a user choice ended representative sampling
	 */
	record ReviewCheckpoint(int reviewPoint, Optional<EvidenceRef> precedingResponseRef,
		Optional<EvidenceRef> authorizationRef, boolean ended)
	{
	}

	/**
	 * A checkpoint reached by one unresolved category.
	 *
	 * @param categoryId category awaiting review
	 * @param reviewPoint confirmed or user-authorized checkpoint
	 * @param precedingResponseRef response authorizing this checkpoint, if any
	 */
	private record DueReview(String categoryId, int reviewPoint, Optional<EvidenceRef> precedingResponseRef)
	{
	}

	/**
	 * Publishes the pending choice for one source-backed unresolved category.
	 *
	 * @param evidence current candidate's validated representative evidence
	 * @param contract confirmed review threshold and category policy
	 * @param categoryId category that reached its unresolved checkpoint
	 * @return immutable indexed review request
	 * @throws IOException if the category or counted evidence does not require review
	 */
	EvidenceRef publishPending(DevelopmentEvidenceService.Snapshot evidence, ConfirmedContract contract,
		String categoryId) throws IOException
	{
		return publishPending(evidence, contract, categoryId,
			contract.developmentReadiness().reviewCheckpoint(), Optional.empty());
	}

	/**
	 * Retains a later checkpoint chosen by an earlier further-evidence response.
	 *
	 * @param evidence current candidate's validated representative evidence
	 * @param contract confirmed category policy
	 * @param categoryId category awaiting another review
	 * @param reviewPoint authorized next checkpoint
	 * @param precedingResponseRef response that established this checkpoint
	 * @return immutable indexed review request
	 * @throws IOException if the response or counted evidence does not authorize this checkpoint
	 */
	EvidenceRef publishPending(DevelopmentEvidenceService.Snapshot evidence, ConfirmedContract contract,
		String categoryId, int reviewPoint, Optional<EvidenceRef> precedingResponseRef) throws IOException
	{
		Objects.requireNonNull(evidence, "evidence");
		Objects.requireNonNull(contract, "contract");
		Objects.requireNonNull(categoryId, "categoryId");
		Objects.requireNonNull(precedingResponseRef, "precedingResponseRef");
		Campaign campaign = evidence.campaign();
		DevelopmentEvidenceService.CategorySeries series = evidence.categorySeries(
			contract.developmentAllocationPolicy().batchSize()).get(categoryId);
		if (series == null || DevelopmentEvidenceDecision.classify(categoryId,
			new DevelopmentEvidenceDecision.CategoryEvidence(series.total(), series.completedBatches(), false, false),
			contract.developmentReadiness(), contract.developmentAllocationPolicy()) !=
			DevelopmentEvidenceDecision.Status.REVIEW_REQUIRED || series.total().trials() < reviewPoint)
			throw new IOException("Development category does not require an evidence review: " + categoryId);
		LineageSummary active = campaign.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow(() ->
			new IOException("Development review has no active confirmed lineage"));
		LineageKey lineage = new LineageKey(active.lineageId(), active.confirmedContractId());
		EvidenceRef epochRef = campaign.measurementState().epochRef();
		EvidenceRef sourceRef = new DevelopmentEvidenceRevisionService(store).publish(evidence);
		DevelopmentReviewRequest expected = new DevelopmentReviewRequest(DevelopmentReviewRequest.SCHEMA_VERSION,
			campaign.campaignId(), lineage, campaign.currentCandidateId(), epochRef, categoryId, sourceRef,
			series.total().successes(), series.total().trials(), reviewPoint, precedingResponseRef);
		validatePreceding(expected, campaign,
			new DevelopmentEvidenceRevisionService(store).read(sourceRef, campaign), contract);
		String key = key(expected);
		Optional<LineageEvidenceRef> indexed = store.findCreateOnceIndex(INDEX, key, lineage, campaign);
		if (indexed.isPresent())
		{
			EvidenceRef reference = indexed.orElseThrow().evidence();
			if (!read(reference, campaign).equals(expected))
				throw new IOException("Development review request conflicts with its counted source");
			return reference;
		}
		EvidenceRef reference = store.appendImmutableJson(REQUESTS, JSON.writeValueAsBytes(expected));
		try
		{
			store.appendCreateOnceIndex(INDEX, key, new LineageEvidenceRef(lineage, reference));
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex(INDEX, key, lineage, campaign);
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		if (!read(reference, campaign).equals(expected))
			throw new IOException("Development review request conflicts with its counted source");
		return reference;
	}

	/**
	 * Finds the current candidate's retained review request after a restart.
	 *
	 * @param categoryId category whose review is requested
	 * @return indexed request, if the category has reached a checkpoint
	 * @throws IOException if the retained request or campaign authority is invalid
	 */
	Optional<EvidenceRef> pending(String categoryId) throws IOException
	{
		Objects.requireNonNull(categoryId, "categoryId");
		Campaign campaign = store.load();
		if (campaign.currentCandidateId() == null || campaign.measurementState() == null)
			return Optional.empty();
		LineageSummary active = campaign.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow(() ->
			new IOException("Development review has no active confirmed lineage"));
		LineageKey lineage = new LineageKey(active.lineageId(), active.confirmedContractId());
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(lineage.contractId());
		int point = contract.developmentReadiness().reviewCheckpoint();
		Optional<EvidenceRef> preceding = Optional.empty();
		while (true)
		{
			String requestKey = key(lineage, campaign.measurementState().epochRef(),
				campaign.currentCandidateId(), categoryId, point);
			Optional<LineageEvidenceRef> indexed = store.findCreateOnceIndex(INDEX, requestKey,
				lineage, campaign);
			if (indexed.isEmpty())
				return Optional.empty();
			EvidenceRef reference = indexed.orElseThrow().evidence();
			DevelopmentReviewRequest request = read(reference, campaign);
			if (!request.lineage().equals(lineage) ||
				!request.candidateId().equals(campaign.currentCandidateId()) ||
				!request.measurementEpochRef().equals(campaign.measurementState().epochRef()) ||
				!request.categoryId().equals(categoryId) || request.reviewPoint() != point ||
				!request.precedingResponseRef().equals(preceding))
				throw new IOException("Development review index conflicts with the current candidate");
			Optional<LineageEvidenceRef> response = store.findCreateOnceIndex(RESPONSE_INDEX,
				reference.evidenceId(), lineage, campaign);
			if (response.isEmpty())
				return Optional.of(reference);
			EvidenceRef responseRef = response.orElseThrow().evidence();
			DevelopmentReviewResponse resolved = readResponse(responseRef, campaign);
			if (resolved.choice() != DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE)
				return Optional.empty();
			preceding = Optional.of(responseRef);
			point = resolved.nextReviewPoint();
		}
	}

	/**
	 * Retains a user choice for the current candidate's pending review.
	 *
	 * @param requestRef source-backed review request
	 * @param choice selected review action
	 * @param nextReviewPoint next threshold for further evidence, or zero otherwise
	 * @return immutable create-once response
	 * @throws IOException if the request is stale or a different response was retained
	 */
	EvidenceRef recordChoice(EvidenceRef requestRef, DevelopmentReviewRequest.Choice choice,
		int nextReviewPoint) throws IOException
	{
		Objects.requireNonNull(requestRef, "requestRef");
		Objects.requireNonNull(choice, "choice");
		Campaign campaign = store.load();
		DevelopmentReviewRequest request = read(requestRef, campaign);
		if (choice == DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE &&
			nextReviewPoint <= request.totalOutcomes() ||
			choice != DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE && nextReviewPoint != 0)
			throw new IllegalArgumentException("Review extension must advance the checkpoint; other choices use zero");
		DevelopmentReviewResponse expected = new DevelopmentReviewResponse(
			DevelopmentReviewResponse.SCHEMA_VERSION, request.campaignId(), request.lineage(), requestRef,
			choice, nextReviewPoint);
		String key = requestRef.evidenceId();
		Optional<LineageEvidenceRef> indexed = store.findCreateOnceIndex(RESPONSE_INDEX, key,
			request.lineage(), campaign);
		if (indexed.isPresent())
		{
			EvidenceRef reference = indexed.orElseThrow().evidence();
			if (!readResponse(reference, campaign).equals(expected))
				throw new IOException("Development review already has a different response");
			return reference;
		}
		if (campaign.status() != CampaignStatus.DEVELOPING || campaign.measurementState() == null ||
			campaign.measurementState().pause().isPresent() ||
			!request.lineage().lineageId().equals(campaign.activeLineageId()) ||
			!request.candidateId().equals(campaign.currentCandidateId()) ||
			!request.measurementEpochRef().equals(campaign.measurementState().epochRef()))
			throw new IOException("Development review response requires the current unpaused candidate");
		DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(store).readCurrent(campaign);
		DevelopmentEvidenceRevision current = new DevelopmentEvidenceRevisionService(store).
			read(new DevelopmentEvidenceRevisionService(store).publish(evidence), campaign);
		DevelopmentEvidenceRevision source = new DevelopmentEvidenceRevisionService(store).
			read(request.sourceRevisionRef(), campaign);
		if (!current.outcomeRefs().equals(source.outcomeRefs()))
			throw new IOException("Development review evidence changed before the user choice");
		EvidenceRef reference = store.appendImmutableJson(RESPONSES, JSON.writeValueAsBytes(expected));
		try
		{
			store.appendCreateOnceIndex(RESPONSE_INDEX, key,
				new LineageEvidenceRef(request.lineage(), reference));
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex(RESPONSE_INDEX, key,
				request.lineage(), campaign);
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		if (!readResponse(reference, campaign).equals(expected))
			throw new IOException("Development review already has a different response");
		return reference;
	}

	/**
	 * Reads a retained response after reopening its campaign store.
	 *
	 * @param reference immutable response reference
	 * @return source-backed review response
	 * @throws IOException if the response or its request is missing or contradictory
	 */
	DevelopmentReviewResponse readResponse(EvidenceRef reference) throws IOException
	{
		return readResponse(reference, store.load());
	}

	/**
	 * Finds a response to one candidate and category's confirmed review checkpoint.
	 *
	 * @param campaign campaign containing the retained candidate
	 * @param lineage confirmed lineage
	 * @param candidateId measured candidate
	 * @param epochRef measurement identity
	 * @param categoryId category requiring review
	 * @param reviewPoint confirmed checkpoint count
	 * @return validated indexed response, if the review has been answered
	 * @throws IOException if a retained request or response is invalid
	 */
	Optional<RetainedResponse> responseFor(Campaign campaign, LineageKey lineage, String candidateId,
		EvidenceRef epochRef, String categoryId, int reviewPoint) throws IOException
	{
		String requestKey = key(lineage, epochRef, candidateId, categoryId, reviewPoint);
		Optional<LineageEvidenceRef> indexedRequest = store.findCreateOnceIndex(INDEX, requestKey,
			lineage, campaign);
		if (indexedRequest.isEmpty())
			return Optional.empty();
		EvidenceRef requestRef = indexedRequest.orElseThrow().evidence();
		DevelopmentReviewRequest request = read(requestRef, campaign);
		if (!request.lineage().equals(lineage) || !request.candidateId().equals(candidateId) ||
			!request.measurementEpochRef().equals(epochRef) || !request.categoryId().equals(categoryId) ||
			request.reviewPoint() != reviewPoint)
			throw new IOException("Development review index conflicts with the category checkpoint");
		Optional<LineageEvidenceRef> indexedResponse = store.findCreateOnceIndex(RESPONSE_INDEX,
			requestRef.evidenceId(), lineage, campaign);
		if (indexedResponse.isEmpty())
			return Optional.empty();
		EvidenceRef responseRef = indexedResponse.orElseThrow().evidence();
		return Optional.of(new RetainedResponse(responseRef, readResponse(responseRef, campaign)));
	}

	/**
	 * Validates a response against an already loaded campaign.
	 *
	 * @param reference indexed response reference
	 * @param campaign owning campaign state
	 * @return validated response
	 * @throws IOException if the response conflicts with its request or index
	 */
	DevelopmentReviewResponse readResponse(EvidenceRef reference, Campaign campaign) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		if (!reference.collection().equals(RESPONSES))
			throw new IOException("Development review response has the wrong evidence collection");
		byte[] bytes = store.readImmutableJson(reference);
		DevelopmentReviewResponse response;
		try
		{
			response = JSON.readValue(bytes, DevelopmentReviewResponse.class);
			if (response == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(response)))
				throw new IOException("Development review response is noncanonical");
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Development review response is malformed", exception);
		}
		DevelopmentReviewRequest request = read(response.requestRef(), campaign);
		if (!response.campaignId().equals(request.campaignId()) ||
			!response.lineage().equals(request.lineage()) ||
			response.choice() == DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE &&
			response.nextReviewPoint() <= request.totalOutcomes() ||
			!store.readCreateOnceIndexForCampaign(RESPONSE_INDEX, response.requestRef().evidenceId(),
				response.lineage(), campaign).evidence().equals(reference))
			throw new IOException("Development review response conflicts with its request or index");
		return response;
	}

	/**
	 * Validates a retained request after a store reopening.
	 *
	 * @param reference indexed request reference
	 * @return source-backed review request
	 * @throws IOException if the request or its counted source is missing or contradictory
	 */
	DevelopmentReviewRequest read(EvidenceRef reference) throws IOException
	{
		return read(reference, store.load());
	}

	/**
	 * Validates a retained request against an already loaded campaign.
	 *
	 * @param reference indexed request reference
	 * @param campaign owning campaign state
	 * @return source-backed review request
	 * @throws IOException if the request or counted source is missing or contradictory
	 */
	DevelopmentReviewRequest read(EvidenceRef reference, Campaign campaign) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		if (!reference.collection().equals(REQUESTS))
			throw new IOException("Development review request has the wrong evidence collection");
		byte[] bytes = store.readImmutableJson(reference);
		DevelopmentReviewRequest request;
		try
		{
			request = JSON.readValue(bytes, DevelopmentReviewRequest.class);
			if (request == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(request)))
				throw new IOException("Development review request is noncanonical");
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Development review request is malformed", exception);
		}
		DevelopmentEvidenceRevision source = new DevelopmentEvidenceRevisionService(store).
			read(request.sourceRevisionRef(), campaign);
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(source.lineage().contractId());
		CategoryCounts actual = new DevelopmentEvidenceRevisionService(store).counts(source,
			request.categoryId());
		if (!request.campaignId().equals(campaign.campaignId()) ||
			!request.campaignId().equals(source.campaignId()) ||
			!request.lineage().equals(source.lineage()) ||
			!request.candidateId().equals(source.candidateId()) ||
			!request.measurementEpochRef().equals(source.measurementEpochRef()) ||
			request.compliantOutcomes() != actual.successes() ||
			request.totalOutcomes() != actual.trials() ||
			!store.readCreateOnceIndexForCampaign(INDEX, key(request), request.lineage(),
				campaign).evidence().equals(reference))
			throw new IOException("Development review request conflicts with counted campaign evidence");
		validatePreceding(request, campaign, source, contract);
		return request;
	}

	/**
	 * Verifies that a later checkpoint follows the response that established it.
	 *
	 * @param request review request being published or read
	 * @param campaign campaign retaining its response chain
	 * @param source counted evidence when the request was created
	 * @param contract confirmed first checkpoint
	 * @throws IOException if the request skips or changes its prior authority
	 */
	private void validatePreceding(DevelopmentReviewRequest request, Campaign campaign,
		DevelopmentEvidenceRevision source, ConfirmedContract contract) throws IOException
	{
		int firstPoint = contract.developmentReadiness().reviewCheckpoint();
		if (request.reviewPoint() < firstPoint ||
			(request.reviewPoint() == firstPoint) != request.precedingResponseRef().isEmpty())
			throw new IOException("Development review checkpoint has no matching prior authority");
		if (request.precedingResponseRef().isEmpty())
			return;
		DevelopmentReviewResponse preceding = readResponse(request.precedingResponseRef().orElseThrow(),
			campaign);
		DevelopmentReviewRequest earlier = read(preceding.requestRef(), campaign);
		DevelopmentEvidenceRevision earlierSource = new DevelopmentEvidenceRevisionService(store).
			read(earlier.sourceRevisionRef(), campaign);
		if (preceding.choice() != DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE ||
			preceding.nextReviewPoint() != request.reviewPoint() ||
			!earlier.campaignId().equals(request.campaignId()) ||
			!earlier.lineage().equals(request.lineage()) ||
			!earlier.candidateId().equals(request.candidateId()) ||
			!earlier.measurementEpochRef().equals(request.measurementEpochRef()) ||
			!earlier.categoryId().equals(request.categoryId()) ||
			earlier.reviewPoint() >= request.reviewPoint() ||
			earlier.totalOutcomes() >= request.totalOutcomes() ||
			earlierSource.sourceCounter() >= source.sourceCounter() ||
			earlierSource.outcomeRefs().size() >= source.outcomeRefs().size() ||
			!source.outcomeRefs().subList(0, earlierSource.outcomeRefs().size()).equals(
				earlierSource.outcomeRefs()))
			throw new IOException("Development review checkpoint conflicts with its preceding response");
	}

	/**
	 * Names one review checkpoint for an unchanged candidate and measurement identity.
	 *
	 * @param request review request to index
	 * @return stable create-once key
	 */
	private static String key(DevelopmentReviewRequest request)
	{
		return key(request.lineage(), request.measurementEpochRef(), request.candidateId(),
			request.categoryId(), request.reviewPoint());
	}

	/**
	 * Names a retained review request without requiring its evidence record.
	 *
	 * @param lineage confirmed lineage
	 * @param epochRef measurement identity
	 * @param candidateId measured candidate
	 * @param categoryId category requiring review
	 * @param reviewPoint confirmed checkpoint count
	 * @return stable create-once key
	 */
	private static String key(LineageKey lineage, EvidenceRef epochRef, String candidateId,
		String categoryId, int reviewPoint)
	{
		return "development-review-v1:" + lineage.lineageId() + ':' + epochRef.evidenceId() + ':' +
			candidateId + ':' + categoryId + ':' + reviewPoint;
	}
}
