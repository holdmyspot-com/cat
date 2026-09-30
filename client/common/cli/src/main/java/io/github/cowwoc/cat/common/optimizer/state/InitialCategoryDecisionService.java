package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidate;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes each balanced first-coverage category choice before its first trial draw.
 */
public final class InitialCategoryDecisionService
{
	private final CampaignLocator locator;
	private final CampaignStore store;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Binds selection to one pinned campaign.
	 *
	 * @param locator registered campaign locator
	 * @param store pinned campaign store
	 */
	public InitialCategoryDecisionService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes or recovers the current balanced first-coverage choice.
	 *
	 * @return indexed immutable category decision
	 * @throws IOException if the current candidate or retained decision conflicts with confirmed inputs
	 */
	public EvidenceRef publish() throws IOException
	{
		CoverageContext context = current();
		EvidenceRef source = context.loadingRef();
		if (context.selection().batchNumber() != 0)
			source = store.appendImmutableJson("coverage-completions", JSON.writeValueAsBytes(completion(context)));
		InitialCategoryDecision expected = decision(context, source);
		String key = key(expected.lineage(), expected.measurementEpochRef(), expected.batchNumber());
		Optional<LineageEvidenceRef> existing = store.findCreateOnceIndex("initial-category-decision-index",
			key, expected.lineage());
		if (existing.isPresent())
			return requireEqual(existing.orElseThrow().evidence(), expected);

		EvidenceRef reference = store.appendImmutableJson("category-decisions", JSON.writeValueAsBytes(expected));
		try
		{
			store.appendCreateOnceIndex("initial-category-decision-index", key,
				new LineageEvidenceRef(expected.lineage(), reference));
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex("initial-category-decision-index",
				key, expected.lineage());
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		return requireEqual(reference, expected);
	}

	/**
	 * Requires the published choice that matches current counted outcomes and confirmed inputs.
	 *
	 * @return indexed immutable category decision
	 * @throws IOException if no decision exists or it is stale or corrupt
	 */
	public EvidenceRef requirePublished() throws IOException
	{
		CoverageContext context = current();
		Optional<LineageEvidenceRef> indexed = store.findCreateOnceIndex("initial-category-decision-index",
			key(context.lineage(), context.campaign().measurementState().epochRef(),
				context.selection().batchNumber()), context.lineage());
		if (indexed.isEmpty())
			throw new IOException("Initial category decision has not been published");
		EvidenceRef reference = indexed.orElseThrow().evidence();
		InitialCategoryDecision retained = store.readInitialCategoryDecision(reference);
		EvidenceRef source = retained.sourceEvidenceRef();
		if (context.selection().batchNumber() != 0 &&
			!store.readInitialCoverageCompletion(source).equals(completion(context)))
			throw new IOException("Initial category decision has stale counted outcomes");
		return requireEqual(reference, decision(context, source));
	}

	/**
	 * Derives the current first-coverage position from counted campaign trials.
	 *
	 * @return current campaign, confirmed inputs, and coverage position
	 * @throws IOException if a required confirmed producer is absent or stale
	 */
	private CoverageContext current() throws IOException
	{
		DevelopmentEvidenceService.Snapshot evidence = new DevelopmentEvidenceService(store).readCurrent();
		Campaign campaign = evidence.campaign();
		CampaignBinding binding = new CampaignBinding(campaign.campaignId(), store.directory());
		LatestConfirmedClaim claim = locator.readLatestConfirmedClaim(binding);
		if (!claim.campaignId().equals(binding.campaignId()) ||
			!claim.lineageId().equals(campaign.activeLineageId()) ||
			campaign.status() != CampaignStatus.DEVELOPING || campaign.measurementState() == null ||
			campaign.measurementState().pause().isPresent())
			throw new IOException("Initial category decision requires the current unpaused confirmed campaign");
		InitialCandidate initial = new InitialCandidateService(locator, store).load();
		if (!initial.candidateSnapshotId().equals(campaign.currentCandidateId()))
			throw new IOException("Initial category decision requires the current initial candidate");

		ContractService contracts = new ContractService(store);
		ConfirmedContract contract = contracts.loadConfirmedContract(claim.contractId());
		List<String> categories = contract.categories().stream().map(Category::id).toList();
		DevelopmentWeaknessGuard.requireNoClearlyWeakCategory(evidence, contract);
		LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
		Map<String, NavigableMap<Integer, EvidenceRef>> outcomes = new HashMap<>();
		for (DevelopmentEvidenceService.Batch batch : evidence.batches())
		{
			InitialCategoryDecision decision = store.readInitialCategoryDecision(batch.decisionRef());
			String category = batch.categoryId();
			if (!decision.eligibleCategories().equals(categories) || !decision.categoryId().equals(category) ||
				!decision.candidateId().equals(campaign.currentCandidateId()))
				throw new IOException("Initial coverage assignment conflicts with its confirmed choice");
			NavigableMap<Integer, EvidenceRef> categoryOutcomes = outcomes.computeIfAbsent(category,
				ignored -> new TreeMap<>());
			for (Map.Entry<Integer, DevelopmentEvidenceService.Slot> entry : batch.slots().entrySet())
			{
				if (categoryOutcomes.containsKey(entry.getKey()))
					throw new IOException("Initial coverage has duplicate assignment ordinals");
				TrialOutcomeRecord outcome = entry.getValue().outcome();
				EvidenceRef outcomeRef = null;
				if (outcome != null)
					outcomeRef = entry.getValue().trial().outcomeEvidence();
				categoryOutcomes.put(entry.getKey(), outcomeRef);
			}
		}
		Map<String, InitialCoveragePolicy.Progress> progress = new HashMap<>();
		for (String category : categories)
		{
			NavigableMap<Integer, EvidenceRef> categoryOutcomes = outcomes.get(category);
			if (categoryOutcomes == null)
				continue;
			int completed = Math.toIntExact(categoryOutcomes.values().stream().filter(Objects::nonNull).count());
			progress.put(category, new InitialCoveragePolicy.Progress(categoryOutcomes.size(), completed));
		}
		InitialCoveragePolicy.Selection selection;
		try
		{
			selection = InitialCoveragePolicy.select(categories, progress,
				contract.developmentAllocationPolicy().batchSize()).orElseThrow(() ->
					new IOException("Initial coverage is complete; a later allocation decision is required"));
		}
		catch (IllegalArgumentException exception)
		{
			throw new IOException("Initial coverage progress is inconsistent", exception);
		}
		String loadingEvidenceId = contracts.loadProposal(contract.sourceProposalId()).loadingEvidenceId();
		return new CoverageContext(binding, lineage, campaign, initial, contract, categories, selection,
			outcomes, new EvidenceRef("prompt-loading", loadingEvidenceId));
	}

	/**
	 * Constructs the immutable decision for a selected first-coverage batch.
	 *
	 * @param context confirmed current coverage
	 * @param source loading evidence or preceding batch completion
	 * @return decision bound to the current source revision
	 */
	private static InitialCategoryDecision decision(CoverageContext context, EvidenceRef source)
	{
		return new InitialCategoryDecision(InitialCategoryDecision.SCHEMA_VERSION,
			context.binding().campaignId(), context.lineage(), context.initial().candidateSnapshotId(),
			context.campaign().measurementState().epochRef(), source,
			context.contract().developmentAllocationPolicy(), context.categories(), List.of(),
			context.selection().categoryId(), EvidencePurpose.DEVELOPMENT,
			context.selection().batchNumber(), 0);
	}

	/**
	 * Binds the next choice to each counted outcome of the immediately preceding batch.
	 *
	 * @param context confirmed current coverage
	 * @return immutable source revision for the next choice
	 * @throws IOException if the preceding choice or one of its outcomes is absent
	 */
	private InitialCoverageCompletion completion(CoverageContext context) throws IOException
	{
		int precedingBatch = context.selection().batchNumber() - 1;
		if (precedingBatch < 0)
			throw new IllegalArgumentException("The first choice has no preceding batch");
		EvidenceRef previousRef = store.readCreateOnceIndex("initial-category-decision-index",
			key(context.lineage(), context.campaign().measurementState().epochRef(), precedingBatch),
			context.lineage()).evidence();
		InitialCategoryDecision previous = store.readInitialCategoryDecision(previousRef);
		String category = context.categories().get(precedingBatch);
		if (previous.batchNumber() != precedingBatch || !previous.categoryId().equals(category) ||
			!previous.candidateId().equals(context.campaign().currentCandidateId()) ||
			!previous.measurementEpochRef().equals(context.campaign().measurementState().epochRef()))
			throw new IOException("Preceding initial coverage choice is stale");
		NavigableMap<Integer, EvidenceRef> previousOutcomes = context.outcomes().get(category);
		int batchSize = context.contract().developmentAllocationPolicy().batchSize();
		if (previousOutcomes == null || previousOutcomes.size() != batchSize)
			throw new IOException("Preceding initial coverage batch is incomplete");
		List<EvidenceRef> ordered = new ArrayList<>(batchSize);
		for (int ordinal = 0; ordinal < batchSize; ++ordinal)
		{
			EvidenceRef outcome = previousOutcomes.get(ordinal);
			if (outcome == null)
				throw new IOException("Preceding initial coverage batch lacks a counted outcome");
			ordered.add(outcome);
		}
		return new InitialCoverageCompletion(InitialCoverageCompletion.SCHEMA_VERSION,
			context.binding().campaignId(), context.lineage(), context.campaign().currentCandidateId(),
			context.campaign().measurementState().epochRef(), previousRef, category, ordered);
	}

	/**
	 * Current source-backed position in balanced first coverage.
	 *
	 * @param binding pinned campaign identity
	 * @param lineage confirmed lineage and contract
	 * @param campaign counted campaign state
	 * @param initial adopted initial candidate
	 * @param contract confirmed category policy
	 * @param categories confirmed category order
	 * @param selection current first-coverage category
	 * @param outcomes assignments and counted outcomes by category and ordinal
	 * @param loadingRef confirmed prompt-loading evidence
	 */
	private record CoverageContext(CampaignBinding binding, LineageKey lineage, Campaign campaign,
		InitialCandidate initial, ConfirmedContract contract, List<String> categories,
		InitialCoveragePolicy.Selection selection, Map<String, NavigableMap<Integer, EvidenceRef>> outcomes,
		EvidenceRef loadingRef)
	{
	}

	/**
	 * Checks that an index points to the current complete choice.
	 *
	 * @param reference indexed choice
	 * @param expected current confirmed choice
	 * @return same reference after validation
	 * @throws IOException if the retained choice is stale or mismatched
	 */
	private EvidenceRef requireEqual(EvidenceRef reference, InitialCategoryDecision expected) throws IOException
	{
		if (!store.readInitialCategoryDecision(reference).equals(expected))
			throw new IOException("Initial category decision conflicts with current confirmed inputs");
		return reference;
	}

	/**
	 * Names the first decision independently of an assignment or draw.
	 *
	 * @param lineage confirmed lineage
	 * @param epochRef current measurement identity
	 * @return create-once index key
	 */
	static String key(LineageKey lineage, EvidenceRef epochRef)
	{
		return "initial-development-v1:" + lineage.lineageId() + ":" + epochRef.evidenceId();
	}

	/**
	 * Names a first-coverage batch without changing the existing first-choice index key.
	 *
	 * @param lineage confirmed lineage
	 * @param epochRef current measurement identity
	 * @param batchNumber zero-based first-coverage batch
	 * @return create-once index key
	 */
	static String key(LineageKey lineage, EvidenceRef epochRef, int batchNumber)
	{
		if (batchNumber < 0)
			throw new IllegalArgumentException("Batch number must be nonnegative");
		String firstKey = key(lineage, epochRef);
		if (batchNumber == 0)
			return firstKey;
		return firstKey + ":" + batchNumber;
	}
}
