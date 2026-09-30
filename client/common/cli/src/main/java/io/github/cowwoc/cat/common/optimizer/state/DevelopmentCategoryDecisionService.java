package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentAllocationPolicy;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentReadiness;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorLabel;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentEvidenceDecision;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes a source-backed representative category choice after initial coverage.
 */
final class DevelopmentCategoryDecisionService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String DECISIONS = "development-category-decisions";
	private static final String INDEX = "development-category-decision-index";

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds choice publication to one registered campaign.
	 *
	 * @param locator registered campaign locator
	 * @param store pinned campaign store
	 */
	DevelopmentCategoryDecisionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes the first choice after every initial batch completes.
	 *
	 * @return indexed immutable category decision
	 * @throws IOException if coverage or confirmed evidence is incomplete
	 */
	EvidenceRef publishFirst() throws IOException
	{
		DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(store).readCurrent();
		Campaign campaign = evidence.campaign();
		CampaignBinding binding = new CampaignBinding(campaign.campaignId(), store.directory());
		LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
		if (campaign.status() != CampaignStatus.DEVELOPING || campaign.measurementState() == null ||
			campaign.measurementState().pause().isPresent() ||
			!claim.campaignId().equals(campaign.campaignId()) ||
			!claim.lineageId().equals(campaign.activeLineageId()) ||
			!new InitialCandidateService(locator, store).load().candidateSnapshotId().equals(
				campaign.currentCandidateId()))
			throw new IOException("Development category choice requires the current unpaused initial candidate");
		LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
		List<String> categories = categories(contract);
		EvidenceRef epochRef = campaign.measurementState().epochRef();
		String key = key(lineage, epochRef, campaign.currentCandidateId(), categories.size());
		Optional<LineageEvidenceRef> existing = store.findCreateOnceIndex(INDEX, key, lineage, campaign);
		if (existing.isPresent())
		{
			EvidenceRef reference = existing.orElseThrow().evidence();
			read(reference, campaign);
			return reference;
		}

		Map<String, DevelopmentEvidenceService.CategorySeries> series = initialSeries(currentCoverage(evidence,
			categories, contract.developmentAllocationPolicy().batchSize()));
		ReviewAuthorities authorities = requireReviewed(evidence, contract, categories);
		DevelopmentBatchSelector.Progress position = new DevelopmentBatchSelector.Progress(
			DevelopmentBatchSelector.Turn.COVERAGE, 0);
		DevelopmentBatchSelector.Selection selected = select(contract.developmentReadiness(),
			contract.developmentAllocationPolicy(), categories, series, position,
			authorities.categories());
		EvidenceRef sourceRef = new DevelopmentEvidenceRevisionService(store).publish(evidence);
		DevelopmentCategoryDecision expected = createDecision(campaign, lineage, contract, sourceRef,
			categories.size(), position, selected, authorities.references());
		EvidenceRef reference = retainDecision(expected);
		try
		{
			publishDecisionIndex(reference, expected);
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex(INDEX, key, lineage, campaign);
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		if (!read(reference, campaign).equals(expected))
			throw new IOException("Development category choice conflicts with the counted source revision");
		return reference;
	}

	/**
	 * Publishes the next choice after a completed batch, or recovers the current unfinished choice.
	 *
	 * @return indexed immutable current or next category decision
	 * @throws IOException if the preceding choice or its counted evidence conflicts with the campaign
	 */
	EvidenceRef publishNext() throws IOException
	{
		DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(store).readCurrent();
		Campaign campaign = evidence.campaign();
		CampaignBinding binding = new CampaignBinding(campaign.campaignId(), store.directory());
		LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
		if (campaign.status() != CampaignStatus.DEVELOPING || campaign.measurementState() == null ||
			campaign.measurementState().pause().isPresent() ||
			!claim.campaignId().equals(campaign.campaignId()) ||
			!claim.lineageId().equals(campaign.activeLineageId()) ||
			!new InitialCandidateService(locator, store).load().candidateSnapshotId().equals(
				campaign.currentCandidateId()))
			throw new IOException("Development category choice requires the current unpaused initial candidate");
		LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
		List<String> categories = categories(contract);
		int batchNumber = evidence.batches().size();
		if (batchNumber <= categories.size())
			throw new IOException("A post-coverage batch has not been assigned");
		DevelopmentEvidenceService.Batch precedingBatch = evidence.batches().getLast();
		if (!precedingBatch.decisionRef().collection().equals(DECISIONS))
			throw new IOException("The preceding development batch has no published choice");
		DevelopmentCategoryDecision previous = read(precedingBatch.decisionRef(), campaign);
		if (previous.batchNumber() != batchNumber - 1 ||
			!previous.categoryId().equals(precedingBatch.categoryId()) ||
			!previous.lineage().equals(lineage) ||
			!previous.candidateId().equals(campaign.currentCandidateId()) ||
			!previous.measurementEpochRef().equals(campaign.measurementState().epochRef()))
			throw new IOException("Preceding development batch conflicts with its published choice");
		int batchSize = contract.developmentAllocationPolicy().batchSize();
		if (precedingBatch.slots().size() > batchSize)
			throw new IOException("Preceding development batch exceeds the confirmed size");
		if (precedingBatch.slots().size() != batchSize || precedingBatch.counts().trials() != batchSize)
			return precedingBatch.decisionRef();

		EvidenceRef epochRef = campaign.measurementState().epochRef();
		String key = key(lineage, epochRef, campaign.currentCandidateId(), batchNumber);
		Optional<LineageEvidenceRef> existing = store.findCreateOnceIndex(INDEX, key, lineage, campaign);
		if (existing.isPresent())
		{
			EvidenceRef reference = existing.orElseThrow().evidence();
			read(reference, campaign);
			return reference;
		}
		Map<String, DevelopmentEvidenceService.CategorySeries> series = evidence.categorySeries(batchSize);
		ReviewAuthorities authorities = requireReviewed(evidence, contract, categories);
		DevelopmentBatchSelector.Selection selected = select(contract.developmentReadiness(),
			contract.developmentAllocationPolicy(), categories, series,
			previous.nextPosition(), authorities.categories());
		EvidenceRef sourceRef = new DevelopmentEvidenceRevisionService(store).publish(evidence);
		DevelopmentCategoryDecision expected = createDecision(campaign, lineage, contract, sourceRef,
			batchNumber, previous.nextPosition(), selected, authorities.references());
		EvidenceRef reference = retainDecision(expected);
		try
		{
			publishDecisionIndex(reference, expected);
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex(INDEX, key, lineage, campaign);
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		if (!read(reference, campaign).equals(expected))
			throw new IOException("Development category choice conflicts with the counted source revision");
		return reference;
	}

	/**
	 * Publishes the create-once index for a retained choice.
	 *
	 * @param reference retained choice identity
	 * @param decision choice represented by the index
	 * @throws IOException if the index cannot be published
	 */
	void publishDecisionIndex(EvidenceRef reference, DevelopmentCategoryDecision decision) throws IOException
	{
		String key = key(decision.lineage(), decision.measurementEpochRef(), decision.candidateId(),
			decision.batchNumber());
		store.appendCreateOnceIndex(INDEX, key, new LineageEvidenceRef(decision.lineage(), reference));
	}

	/**
	 * Requires the campaign's published choice index to name the exact retained reference.
	 *
	 * @param reference retained choice identity
	 * @param decision decoded choice
	 * @param campaign validated campaign snapshot
	 * @throws IOException if the index is missing or names another choice
	 */
	void requirePublishedDecisionIndex(EvidenceRef reference, DevelopmentCategoryDecision decision,
		Campaign campaign) throws IOException
	{
		String key = key(decision.lineage(), decision.measurementEpochRef(), decision.candidateId(),
			decision.batchNumber());
		if (!store.readCreateOnceIndexForCampaign(INDEX, key, decision.lineage(), campaign).evidence().
			equals(reference))
			throw new IOException("Development category choice differs from its published index");
	}

	/**
	 * Binds a selected category and cursor to its already validated campaign and counted source.
	 *
	 * @param campaign validated current campaign
	 * @param lineage confirmed lineage selected by the owning caller
	 * @param contract confirmed category and allocation policy
	 * @param sourceRef retained counted source
	 * @param batchNumber next zero-based development batch number
	 * @param position turn and cursor used to select the category
	 * @param selected category and next cursor returned by the selection policy
	 * @param authorizations retained responses permitting further sampling
	 * @return choice ready for canonical persistence and index publication
	 */
	static DevelopmentCategoryDecision createDecision(Campaign campaign, LineageKey lineage,
		ConfirmedContract contract, EvidenceRef sourceRef, int batchNumber,
		DevelopmentBatchSelector.Progress position, DevelopmentBatchSelector.Selection selected,
		List<EvidenceRef> authorizations)
	{
		return new DevelopmentCategoryDecision(DevelopmentCategoryDecision.SCHEMA_VERSION,
			campaign.campaignId(), lineage, campaign.currentCandidateId(), campaign.measurementState().epochRef(),
			sourceRef, contract.developmentAllocationPolicy(), categories(contract), List.of(), selected.categoryId(),
			EvidencePurpose.DEVELOPMENT, batchNumber, position, selected.next(), authorizations);
	}

	/**
	 * Writes one choice record without loading its source history or publishing its authorization index.
	 *
	 * @param decision constructed choice whose authority the owning caller validates
	 * @return immutable canonical record identity
	 * @throws IOException if the record cannot be retained
	 */
	EvidenceRef retainDecision(DevelopmentCategoryDecision decision) throws IOException
	{
		return store.appendImmutableJson(DECISIONS, JSON.writeValueAsBytes(decision));
	}

	/**
	 * Decodes the canonical choice record before its owning reader validates source and index authority.
	 *
	 * @param bytes immutable choice bytes
	 * @return decoded choice with its original identities and cursors
	 * @throws IOException if the record is malformed, empty, or noncanonical
	 */
	static DevelopmentCategoryDecision decodeRecord(byte[] bytes) throws IOException
	{
		try
		{
			DevelopmentCategoryDecision decision = JSON.readValue(bytes, DevelopmentCategoryDecision.class);
			if (decision == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(decision)))
				throw new IOException("Development category choice is noncanonical");
			return decision;
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Development category choice is malformed", exception);
		}
	}

	/**
	 * Revalidates a published choice and its counted source revision.
	 *
	 * @param reference indexed decision reference
	 * @return validated development choice
	 * @throws IOException if the decision or source conflicts with the campaign
	 */
	DevelopmentCategoryDecision read(EvidenceRef reference) throws IOException
	{
		return read(reference, store.load());
	}

	/**
	 * Revalidates a retained choice against an already validated campaign.
	 *
	 * @param reference indexed decision reference
	 * @param campaign validated campaign state
	 * @return validated development choice
	 * @throws IOException if the historical source is missing or contradictory
	 */
	DevelopmentCategoryDecision read(EvidenceRef reference, Campaign campaign) throws IOException
	{
		return read(reference, new ValidationSession(campaign));
	}

	/**
	 * Reuses choices verified while reading the same campaign snapshot.
	 *
	 * @param reference indexed decision reference
	 * @param session validation state for one campaign snapshot
	 * @return validated development choice
	 * @throws IOException if the decision or its source conflicts with the campaign
	 */
	DevelopmentCategoryDecision read(EvidenceRef reference, ValidationSession session) throws IOException
	{
		return validate(reference, session, new HashSet<>()).decision();
	}

	/**
	 * Reconstructs a choice and its completed category histories from the retained source chain.
	 *
	 * @param reference indexed choice reference
	 * @param session validation state for one already validated campaign
	 * @param visited choices encountered while following preceding batches
	 * @return validated choice, source revision, and category histories
	 * @throws IOException if a choice or predecessor is missing, cyclic, or contradictory
	 */
	private ValidatedChoice validate(EvidenceRef reference, ValidationSession session, Set<EvidenceRef> visited)
		throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		if (!reference.collection().equals(DECISIONS))
			throw new IOException("Development category choice has the wrong evidence collection");
		if (!visited.add(reference))
			throw new IOException("Development category choices contain a predecessor cycle");
		ValidatedChoice cached = session.verified.get(reference);
		if (cached != null)
			return cached;
		Campaign campaign = session.campaign;
		DevelopmentCategoryDecision decision = decodeRecord(store.readImmutableJson(reference));
		requirePublishedDecisionIndex(reference, decision, campaign);
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(decision.lineage().contractId());
		List<String> categories = categories(contract);
		if (!decision.campaignId().equals(campaign.campaignId()) ||
			!decision.confirmedCategories().equals(categories) ||
			decision.allocationPolicy() != contract.developmentAllocationPolicy() ||
			decision.batchNumber() < categories.size())
			throw new IOException("Development category choice differs from its confirmed contract");
		DevelopmentEvidenceRevision revision = new DevelopmentEvidenceRevisionService(store).
			read(decision.sourceRevisionRef(), campaign);
		if (!revision.campaignId().equals(decision.campaignId()) ||
			!revision.lineage().equals(decision.lineage()) ||
			!revision.candidateId().equals(decision.candidateId()) ||
			!revision.measurementEpochRef().equals(decision.measurementEpochRef()))
			throw new IOException("Development category choice names a different counted source");
		Map<String, DevelopmentEvidenceService.CategorySeries> series;
		DevelopmentBatchSelector.Progress position;
		if (decision.batchNumber() == categories.size())
		{
			series = initialSeries(retainedCoverage(revision, decision, categories,
				contract.developmentAllocationPolicy().batchSize()));
			position = new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.COVERAGE, 0);
		}
		else
		{
			EvidenceRef previousRef = store.readCreateOnceIndexForCampaign(INDEX,
				key(decision.lineage(), decision.measurementEpochRef(), decision.candidateId(),
					decision.batchNumber() - 1), decision.lineage(), campaign).evidence();
			ValidatedChoice previous = validate(previousRef, session, visited);
			if (previous.decision().batchNumber() != decision.batchNumber() - 1 ||
				!previous.decision().lineage().equals(decision.lineage()) ||
				!previous.decision().candidateId().equals(decision.candidateId()) ||
				!previous.decision().measurementEpochRef().equals(decision.measurementEpochRef()))
				throw new IOException("Development category choice has a foreign preceding batch");
			series = continuedSeries(previous, previousRef, revision, decision,
				contract.developmentAllocationPolicy().batchSize());
			position = previous.decision().nextPosition();
		}
		Set<String> authorized = verifiedReviewAuthorizations(decision, revision, series, contract, campaign);
		DevelopmentBatchSelector.Selection selected = select(contract.developmentReadiness(),
			contract.developmentAllocationPolicy(), categories, series, position, authorized);
		if (!decision.stoppedCategories().isEmpty() || !decision.position().equals(position) ||
			!decision.categoryId().equals(selected.categoryId()) ||
			!decision.nextPosition().equals(selected.next()))
			throw new IOException("Development category choice disagrees with its counted source");
		ValidatedChoice result = new ValidatedChoice(decision, revision, series);
		session.verified.put(reference, result);
		return result;
	}

	/**
	 * Lists category names in confirmed order.
	 *
	 * @param contract confirmed policy
	 * @return ordered category identifiers
	 */
	private static List<String> categories(ConfirmedContract contract)
	{
		return contract.categories().stream().map(Category::id).toList();
	}

	/**
	 * Makes each initial count the first completed batch in its category.
	 *
	 * @param totals complete initial category counts
	 * @return category histories at the end of initial coverage
	 */
	private static Map<String, DevelopmentEvidenceService.CategorySeries> initialSeries(
		Map<String, CategoryCounts> totals)
	{
		Map<String, DevelopmentEvidenceService.CategorySeries> result = new LinkedHashMap<>();
		for (Map.Entry<String, CategoryCounts> entry : totals.entrySet())
			result.put(entry.getKey(), new DevelopmentEvidenceService.CategorySeries(entry.getValue(),
				List.of(entry.getValue())));
		return result;
	}

	/**
	 * Adds exactly one counted batch to the preceding choice's category histories.
	 *
	 * @param previous validated choice that authorized the completed batch
	 * @param previousRef immutable reference cited by its assignments
	 * @param revision counted source for the next choice
	 * @param decision next choice being validated
	 * @param batchSize confirmed batch size
	 * @return category histories at the next choice boundary
	 * @throws IOException if the source adds anything other than the preceding batch
	 */
	private Map<String, DevelopmentEvidenceService.CategorySeries> continuedSeries(ValidatedChoice previous,
		EvidenceRef previousRef, DevelopmentEvidenceRevision revision, DevelopmentCategoryDecision decision,
		int batchSize) throws IOException
	{
		List<EvidenceRef> outcomes = completedBatchOutcomes(previous.revision(), revision, batchSize);
		Set<Integer> ordinals = new HashSet<>();
		int successes = 0;
		for (EvidenceRef outcomeRef : outcomes)
		{
			TrialOutcomeRecord outcome = store.readTrialOutcome(outcomeRef);
			TrialAssignment assignment = store.readTrialAssignment(outcome.assignmentRef());
			requirePrecedingBatchAssignment(assignment, previousRef, previous.decision(), decision, batchSize, ordinals);
			if (outcome.response().label() == EvaluatorLabel.COMPLIANT)
				successes = Math.incrementExact(successes);
		}
		if (ordinals.size() != batchSize)
			throw new IOException("Development category source lacks a complete preceding batch");
		return appendCompletedBatch(previous.series(), previous.decision().categoryId(),
			new CategoryCounts(successes, batchSize));
	}

	/**
	 * Extends one category's total and completed history while preserving every other category and the input.
	 *
	 * @param previous validated category histories before the completed batch
	 * @param category category that authorized the batch
	 * @param added counted successes and trials in that completed batch
	 * @return extended category histories
	 */
	static Map<String, DevelopmentEvidenceService.CategorySeries> appendCompletedBatch(
		Map<String, DevelopmentEvidenceService.CategorySeries> previous, String category, CategoryCounts added)
	{
		Map<String, DevelopmentEvidenceService.CategorySeries> result = new LinkedHashMap<>(previous);
		DevelopmentEvidenceService.CategorySeries before = result.get(category);
		CategoryCounts total = new CategoryCounts(Math.addExact(before.total().successes(), added.successes()),
			Math.addExact(before.total().trials(), added.trials()));
		List<CategoryCounts> completed = new ArrayList<>(before.completedBatches());
		completed.add(added);
		result.put(category, new DevelopmentEvidenceService.CategorySeries(total, completed));
		return result;
	}

	/**
	 * Requires an advancing revision that preserves the prior prefix and adds exactly one batch.
	 *
	 * @param previous validated preceding revision
	 * @param current validated next revision
	 * @param batchSize confirmed batch size
	 * @return added references without reading or processing their outcome records
	 * @throws IOException if the counter, prefix, or added count conflicts
	 */
	static List<EvidenceRef> completedBatchOutcomes(DevelopmentEvidenceRevision previous,
		DevelopmentEvidenceRevision current, int batchSize) throws IOException
	{
		List<EvidenceRef> priorOutcomes = previous.outcomeRefs();
		List<EvidenceRef> outcomes = current.outcomeRefs();
		if (current.sourceCounter() <= previous.sourceCounter() ||
			outcomes.size() != priorOutcomes.size() + batchSize ||
			!outcomes.subList(0, priorOutcomes.size()).equals(priorOutcomes))
			throw new IOException("Development category source does not extend the preceding completed batch");
		return outcomes.subList(priorOutcomes.size(), outcomes.size());
	}

	/**
	 * Binds one decoded assignment to the preceding choice and registers its distinct batch ordinal.
	 *
	 * @param assignment canonical assignment read from one outcome
	 * @param previousRef exact preceding choice reference
	 * @param previous preceding category choice
	 * @param current next choice being validated
	 * @param batchSize confirmed batch size
	 * @param ordinals ordinals already accepted in this batch validation
	 * @throws IOException if identity, category, ordinal range, or uniqueness conflicts
	 */
	static void requirePrecedingBatchAssignment(TrialAssignment assignment, EvidenceRef previousRef,
		DevelopmentCategoryDecision previous, DevelopmentCategoryDecision current, int batchSize,
		Set<Integer> ordinals) throws IOException
	{
		if (!assignment.categoryDecisionRef().equals(previousRef) ||
			!assignment.lineage().equals(current.lineage()) ||
			!assignment.candidateId().equals(current.candidateId()) ||
			!assignment.measurementEpochRef().equals(current.measurementEpochRef()) ||
			!assignment.instance().definition().categoryId().equals(previous.categoryId()) ||
			assignment.ordinal() >= batchSize || !ordinals.add(assignment.ordinal()))
			throw new IOException("Development category source contains a foreign preceding-batch outcome");
	}

	/**
	 * Requires one complete first batch per confirmed category.
	 *
	 * @param evidence current representative batches
	 * @param categories confirmed category order
	 * @param batchSize confirmed batch size
	 * @return current candidate totals at the first post-coverage boundary
	 * @throws IOException if a batch is incomplete or a later batch has begun
	 */
	private Map<String, CategoryCounts> currentCoverage(DevelopmentEvidenceService.Snapshot evidence,
		List<String> categories, int batchSize) throws IOException
	{
		if (evidence.batches().size() != categories.size())
			throw new IOException("Initial development coverage is incomplete or a later batch has begun");
		Map<String, CategoryCounts> totals = new LinkedHashMap<>();
		for (int index = 0; index < categories.size(); ++index)
		{
			DevelopmentEvidenceService.Batch batch = evidence.batches().get(index);
			InitialCategoryDecision initial = store.readInitialCategoryDecision(batch.decisionRef());
			CategoryCounts counts = batch.counts();
			if (!batch.categoryId().equals(categories.get(index)) || initial.batchNumber() != index ||
				!initial.eligibleCategories().equals(categories) ||
				!initial.candidateId().equals(evidence.campaign().currentCandidateId()) ||
				!initial.measurementEpochRef().equals(evidence.campaign().measurementState().epochRef()) ||
				batch.slots().size() != batchSize || counts.trials() != batchSize)
				throw new IOException("Initial development coverage has an incomplete or foreign batch");
			totals.put(batch.categoryId(), counts);
		}
		return totals;
	}

	/**
	 * Reconstructs first-coverage counts from the historical outcome prefix.
	 *
	 * @param revision validated outcome prefix
	 * @param decision choice bound to the prefix
	 * @param categories confirmed category order
	 * @param batchSize confirmed batch size
	 * @return one complete count for each first-coverage category
	 * @throws IOException if an outcome is missing or cites another batch
	 */
	private Map<String, CategoryCounts> retainedCoverage(DevelopmentEvidenceRevision revision,
		DevelopmentCategoryDecision decision, List<String> categories, int batchSize) throws IOException
	{
		if (revision.outcomeRefs().size() != categories.size() * batchSize)
			throw new IOException("Development category source has the wrong first-coverage outcome count");
		Map<String, Integer> successes = new HashMap<>();
		Map<String, Set<Integer>> ordinals = new HashMap<>();
		Map<EvidenceRef, InitialCategoryDecision> choices = new HashMap<>();
		for (EvidenceRef outcomeRef : revision.outcomeRefs())
		{
			TrialOutcomeRecord outcome = store.readTrialOutcome(outcomeRef);
			TrialAssignment assignment = store.readTrialAssignment(outcome.assignmentRef());
			InitialCategoryDecision initial = choices.get(assignment.categoryDecisionRef());
			if (initial == null)
			{
				initial = store.readInitialCategoryDecision(assignment.categoryDecisionRef());
				choices.put(assignment.categoryDecisionRef(), initial);
			}
			String category = assignment.instance().definition().categoryId();
			if (initial.batchNumber() >= categories.size() ||
				!categories.get(initial.batchNumber()).equals(category) ||
				!initial.eligibleCategories().equals(categories) ||
				!initial.lineage().equals(decision.lineage()) ||
				!initial.candidateId().equals(decision.candidateId()) ||
				!initial.measurementEpochRef().equals(decision.measurementEpochRef()) ||
				assignment.ordinal() >= batchSize ||
				!ordinals.computeIfAbsent(category, ignored -> new HashSet<>()).add(assignment.ordinal()))
				throw new IOException("Development category source has a foreign initial-coverage outcome");
			if (outcome.response().label() == EvaluatorLabel.COMPLIANT)
				successes.merge(category, 1, Math::addExact);
		}
		Map<String, CategoryCounts> totals = new LinkedHashMap<>();
		for (String category : categories)
		{
			if (ordinals.getOrDefault(category, Set.of()).size() != batchSize)
				throw new IOException("Development category source lacks a completed initial batch");
			totals.put(category, new CategoryCounts(successes.getOrDefault(category, 0), batchSize));
		}
		return totals;
	}

	/**
	 * Applies readiness precedence before selecting a representative batch.
	 *
	 * @param readiness confirmed readiness policy
	 * @param allocation confirmed batch allocation policy
	 * @param categories confirmed category order
	 * @param series counted category totals and completed batches
	 * @param position next turn
	 * @param authorized categories whose retained review response permits further sampling
	 * @return next category and cursor
	 * @throws IOException if diagnosis, review, or certification must happen first
	 */
	static DevelopmentBatchSelector.Selection select(DevelopmentReadiness readiness,
		DevelopmentAllocationPolicy allocation, List<String> categories,
		Map<String, DevelopmentEvidenceService.CategorySeries> series,
		DevelopmentBatchSelector.Progress position, Set<String> authorized) throws IOException
	{
		Set<String> unresolved = new HashSet<>();
		Map<String, CategoryCounts> totals = new LinkedHashMap<>();
		for (String category : categories)
		{
			DevelopmentEvidenceService.CategorySeries categorySeries = series.get(category);
			if (categorySeries == null)
				throw new IOException("Development category lacks representative evidence: " + category);
			CategoryCounts counts = categorySeries.total();
			totals.put(category, counts);
			DevelopmentEvidenceDecision.Status status = DevelopmentEvidenceDecision.classify(category,
				new DevelopmentEvidenceDecision.CategoryEvidence(counts, categorySeries.completedBatches(),
					false, false),
				readiness, allocation);
			if (status == DevelopmentEvidenceDecision.Status.UNCERTAIN ||
				status == DevelopmentEvidenceDecision.Status.REVIEW_REQUIRED && authorized.contains(category))
				unresolved.add(category);
			else if (status != DevelopmentEvidenceDecision.Status.READY)
				throw new IOException("Development category requires a higher-priority readiness decision: " + category);
		}
		if (unresolved.isEmpty())
			throw new IOException("All development categories are ready for certification");
		return DevelopmentBatchSelector.select(categories, totals, Set.copyOf(categories), unresolved, position).
			orElseThrow(() -> new IOException("No eligible development category remains"));
	}

	/**
	 * Retains an unresolved checkpoint before another category choice can be published.
	 *
	 * @param evidence current candidate's representative evidence
	 * @param contract confirmed readiness and allocation policy
	 * @param categories confirmed category order
	 * @return responses authorizing continued sampling for reviewed categories
	 * @throws IOException if a category awaits a review choice
	 */
	private ReviewAuthorities requireReviewed(DevelopmentEvidenceService.Snapshot evidence, ConfirmedContract contract,
		List<String> categories) throws IOException
	{
		Campaign campaign = evidence.campaign();
		LineageSummary active = campaign.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow(() ->
			new IOException("Development category review has no active confirmed lineage"));
		LineageKey lineage = new LineageKey(active.lineageId(), active.confirmedContractId());
		DevelopmentReviewService reviews = new DevelopmentReviewService(store);
		List<EvidenceRef> references = new ArrayList<>();
		Set<String> authorized = new HashSet<>();
		Map<String, DevelopmentEvidenceService.CategorySeries> series = evidence.categorySeries(
			contract.developmentAllocationPolicy().batchSize());
		for (String category : categories)
		{
			DevelopmentEvidenceService.CategorySeries categorySeries = series.get(category);
			if (categorySeries == null)
				continue;
			DevelopmentEvidenceDecision.Status status = DevelopmentEvidenceDecision.classify(category,
				new DevelopmentEvidenceDecision.CategoryEvidence(categorySeries.total(),
					categorySeries.completedBatches(), false, false),
				contract.developmentReadiness(), contract.developmentAllocationPolicy());
			if (status != DevelopmentEvidenceDecision.Status.REVIEW_REQUIRED)
				continue;
			DevelopmentReviewService.ReviewCheckpoint checkpoint = DevelopmentReviewService.resolveCheckpoint(
				categorySeries.total().trials(), contract.developmentReadiness().reviewCheckpoint(), point ->
					reviews.responseFor(campaign, lineage, campaign.currentCandidateId(),
						campaign.measurementState().epochRef(), category, point));
			if (checkpoint.ended())
				throw new IOException("Development review choice does not authorize another batch: " + category);
			if (checkpoint.authorizationRef().isEmpty())
			{
				EvidenceRef requestRef = reviews.publishPending(evidence, contract, category,
					checkpoint.reviewPoint(), checkpoint.precedingResponseRef());
				throw new IOException("Development evidence review is required before another batch: " +
					requestRef.evidenceId());
			}
			references.add(checkpoint.authorizationRef().orElseThrow());
			authorized.add(category);
		}
		return new ReviewAuthorities(references, authorized);
	}

	/**
	 * Rechecks the exact responses cited by an immutable category decision.
	 *
	 * @param decision category choice carrying its review authority
	 * @param revision counted source at the choice boundary
	 * @param series category histories reconstructed at that boundary
	 * @param contract confirmed readiness rule
	 * @param campaign campaign retaining the decision and responses
	 * @return categories with valid further-evidence authority
	 * @throws IOException if a response is missing, stale, or unrelated to the counted source
	 */
	private Set<String> verifiedReviewAuthorizations(DevelopmentCategoryDecision decision,
		DevelopmentEvidenceRevision revision, Map<String, DevelopmentEvidenceService.CategorySeries> series,
		ConfirmedContract contract, Campaign campaign) throws IOException
	{
		Set<String> authorized = new HashSet<>();
		DevelopmentReviewService reviews = new DevelopmentReviewService(store);
		for (EvidenceRef responseRef : decision.reviewAuthorizationRefs())
		{
			DevelopmentReviewResponse response = reviews.readResponse(responseRef, campaign);
			DevelopmentReviewRequest request = reviews.read(response.requestRef(), campaign);
			DevelopmentEvidenceService.CategorySeries categorySeries = series.get(request.categoryId());
			if (categorySeries == null)
				throw new IOException("Development review authorizes an absent category");
			DevelopmentEvidenceRevision reviewSource = new DevelopmentEvidenceRevisionService(store).
				read(request.sourceRevisionRef(), campaign);
			if (!request.campaignId().equals(decision.campaignId()) ||
				!request.lineage().equals(decision.lineage()) ||
				!request.candidateId().equals(decision.candidateId()) ||
				!request.measurementEpochRef().equals(decision.measurementEpochRef()) ||
				response.choice() != DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE ||
				request.totalOutcomes() > categorySeries.total().trials() ||
				categorySeries.total().trials() >= response.nextReviewPoint() ||
				reviewSource.sourceCounter() > revision.sourceCounter() ||
				reviewSource.outcomeRefs().size() > revision.outcomeRefs().size() ||
				!revision.outcomeRefs().subList(0, reviewSource.outcomeRefs().size()).equals(
					reviewSource.outcomeRefs()) ||
				DevelopmentEvidenceDecision.classify(request.categoryId(),
					new DevelopmentEvidenceDecision.CategoryEvidence(categorySeries.total(),
						categorySeries.completedBatches(), false, false), contract.developmentReadiness(),
					contract.developmentAllocationPolicy()) != DevelopmentEvidenceDecision.Status.REVIEW_REQUIRED ||
				!authorized.add(request.categoryId()))
				throw new IOException("Development category choice has invalid review authority");
		}
		return authorized;
	}

	/**
	 * Responses and the categories they authorize at one choice boundary.
	 *
	 * @param references exact response evidence retained by the category decision
	 * @param categories categories authorized by those responses
	 */
	private record ReviewAuthorities(List<EvidenceRef> references, Set<String> categories)
	{
		/**
		 * Copies the review authority set before publication.
		 *
		 * @param references exact response evidence
		 * @param categories authorized categories
		 */
		ReviewAuthorities
		{
			references = List.copyOf(references);
			categories = Set.copyOf(categories);
		}
	}

	/**
	 * Names one post-coverage choice.
	 *
	 * @param lineage confirmed lineage
	 * @param epochRef measured epoch
	 * @param candidateId measured candidate
	 * @param batchNumber zero-based development batch number
	 * @return logical choice identity
	 */
	private static String key(LineageKey lineage, EvidenceRef epochRef, String candidateId, int batchNumber)
	{
		return "development-category-v1:" + lineage.lineageId() + ':' + epochRef.evidenceId() + ':' +
			candidateId + ':' + batchNumber;
	}

	/**
	 * Shares verified predecessor choices only within one campaign read.
	 */
	static final class ValidationSession
	{
		private final Campaign campaign;
		private final Map<EvidenceRef, ValidatedChoice> verified = new HashMap<>();

		/**
		 * Binds cached choices to one validated campaign snapshot.
		 *
		 * @param campaign campaign being checked
		 */
		ValidationSession(Campaign campaign)
		{
			this.campaign = Objects.requireNonNull(campaign, "campaign");
		}
	}

	/**
	 * A validated choice and the category histories at its publication boundary.
	 *
	 * @param decision published category choice
	 * @param revision exact counted source cited by that choice
	 * @param series completed category histories through that source
	 */
	private record ValidatedChoice(DevelopmentCategoryDecision decision, DevelopmentEvidenceRevision revision,
		Map<String, DevelopmentEvidenceService.CategorySeries> series)
	{
	}
}
