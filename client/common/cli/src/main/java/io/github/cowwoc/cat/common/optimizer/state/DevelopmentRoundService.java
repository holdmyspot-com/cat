package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.InitialCandidateService;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.ConfirmedContract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractService;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentEvidenceDecision;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts and reopens an initial development round from counted representative outcomes.
 */
final class DevelopmentRoundService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String ROUNDS = "development-round-starts";
	private static final String INDEX = "development-round-start-index";

	private final CampaignLocator locator;
	private final CampaignStore store;

	/**
	 * Binds round publication to the selected campaign.
	 *
	 * @param locator registered campaign locator
	 * @param store pinned campaign store
	 */
	DevelopmentRoundService(CampaignLocator locator, CampaignStore store)
	{
		this.locator = Objects.requireNonNull(locator, "locator");
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes the initial weak-category round once.
	 *
	 * @return immutable round start
	 * @throws IOException if the current candidate has no valid weak category
	 */
	EvidenceRef publishInitial() throws IOException
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
			throw new IOException("Initial development round requires the current unpaused original candidate");
		LineageKey lineage = new LineageKey(claim.lineageId(), claim.contractId());
		EvidenceRef epochRef = campaign.measurementState().epochRef();
		String key = key(lineage, epochRef, campaign.currentCandidateId());
		Optional<LineageEvidenceRef> existing = store.findCreateOnceIndex(INDEX, key, lineage, campaign);
		if (existing.isPresent())
		{
			EvidenceRef reference = existing.orElseThrow().evidence();
			read(reference, campaign);
			return reference;
		}

		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(claim.contractId());
		List<String> failing = weakCategories(evidence, contract);
		if (failing.isEmpty())
			throw new IOException("Initial development round requires a clearly weak category");
		DevelopmentEvidenceRevisionService revisions = new DevelopmentEvidenceRevisionService(store);
		EvidenceRef sourceRef = revisions.publish(evidence);
		DevelopmentEvidenceRevision source = revisions.read(sourceRef, campaign);
		if (!failing.equals(weakCategories(source, contract)))
			throw new IOException("Initial development round conflicts with its counted source");
		DevelopmentRoundStart expected = new DevelopmentRoundStart(DevelopmentRoundStart.SCHEMA_VERSION,
			campaign.campaignId(), lineage, campaign.currentCandidateId(), epochRef, sourceRef,
			contract.developmentRoundPolicy(), contract.developmentReadiness().comparisonPolicy(), failing);
		EvidenceRef reference = store.appendImmutableJson(ROUNDS, JSON.writeValueAsBytes(expected));
		try
		{
			store.appendCreateOnceIndexIfCurrent(INDEX, key, new LineageEvidenceRef(lineage, reference),
				campaign);
		}
		catch (IOException publicationFailure)
		{
			Optional<LineageEvidenceRef> winner = store.findCreateOnceIndex(INDEX, key, lineage);
			if (winner.isEmpty())
				throw publicationFailure;
			reference = winner.orElseThrow().evidence();
		}
		read(reference);
		return reference;
	}

	/**
	 * Reads and verifies an initial round after restart.
	 *
	 * @param reference immutable round start
	 * @return validated round
	 * @throws IOException if its source or authority is missing or contradictory
	 */
	DevelopmentRoundStart read(EvidenceRef reference) throws IOException
	{
		return read(reference, store.load());
	}

	/**
	 * Verifies the round against an already validated campaign.
	 *
	 * @param reference immutable round start
	 * @param campaign validated campaign state
	 * @return source-backed round
	 * @throws IOException if the round or its counted source is invalid
	 */
	private DevelopmentRoundStart read(EvidenceRef reference, Campaign campaign) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		if (!reference.collection().equals(ROUNDS))
			throw new IOException("Initial development round has the wrong evidence collection");
		byte[] bytes = store.readImmutableJson(reference);
		DevelopmentRoundStart round;
		try
		{
			round = JSON.readValue(bytes, DevelopmentRoundStart.class);
			if (round == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(round)))
				throw new IOException("Initial development round is noncanonical");
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Initial development round is malformed", exception);
		}
		DevelopmentEvidenceRevision source = new DevelopmentEvidenceRevisionService(store).
			read(round.sourceRevisionRef(), campaign);
		ConfirmedContract contract = new ContractService(store).loadConfirmedContract(
			round.lineage().contractId());
		if (!round.campaignId().equals(campaign.campaignId()) ||
			!round.campaignId().equals(source.campaignId()) ||
			!round.lineage().equals(source.lineage()) ||
			!round.incumbentCandidateId().equals(source.candidateId()) ||
			campaign.transitionReceipts().stream().noneMatch(receipt ->
				receipt.kind() == TransitionKind.INITIAL_CANDIDATE &&
				receipt.result().lineageId().equals(round.lineage().lineageId()) &&
				receipt.result().candidateId().equals(round.incumbentCandidateId())) ||
			!round.measurementEpochRef().equals(source.measurementEpochRef()) ||
			!round.roundPolicy().equals(contract.developmentRoundPolicy()) ||
			round.comparisonPolicy() != contract.developmentReadiness().comparisonPolicy() ||
			!round.failingCategories().equals(weakCategories(source, contract)) ||
			!store.readCreateOnceIndexForCampaign(INDEX, key(round.lineage(),
				round.measurementEpochRef(), round.incumbentCandidateId()), round.lineage(), campaign).
				evidence().equals(reference))
			throw new IOException("Initial development round conflicts with counted campaign evidence");
		return round;
	}

	/**
	 * Finds weak categories in the current validated representative snapshot.
	 *
	 * @param evidence current candidate's validated representative evidence
	 * @param contract confirmed readiness and category order
	 * @return weak category ids in confirmed order
	 */
	private List<String> weakCategories(DevelopmentEvidenceService.Snapshot evidence,
		ConfirmedContract contract)
	{
		Map<String, DevelopmentEvidenceService.CategorySeries> series = evidence.categorySeries(
			contract.developmentAllocationPolicy().batchSize());
		List<String> failing = new ArrayList<>();
		for (Category category : contract.categories())
		{
			DevelopmentEvidenceService.CategorySeries history = series.get(category.id());
			if (history == null)
				continue;
			DevelopmentEvidenceDecision.Status status = DevelopmentEvidenceDecision.classify(category.id(),
				new DevelopmentEvidenceDecision.CategoryEvidence(history.total(),
					history.completedBatches(), false, false), contract.developmentReadiness(),
				contract.developmentAllocationPolicy());
			if (status == DevelopmentEvidenceDecision.Status.WEAK)
				failing.add(category.id());
		}
		return List.copyOf(failing);
	}

	/**
	 * Reconstructs weak categories from the exact retained outcome prefix.
	 *
	 * @param source counted outcome prefix named by the round
	 * @param contract confirmed readiness and category order
	 * @return weak category ids in confirmed order
	 * @throws IOException if an outcome or assignment cannot be read
	 */
	private List<String> weakCategories(DevelopmentEvidenceRevision source, ConfirmedContract contract)
		throws IOException
	{
		DevelopmentEvidenceRevisionService revisions = new DevelopmentEvidenceRevisionService(store);
		Map<String, CategoryCounts> categoryCounts = revisions.counts(source);
		List<String> failing = new ArrayList<>();
		for (Category category : contract.categories())
		{
			CategoryCounts counts = categoryCounts.getOrDefault(category.id(), new CategoryCounts(0, 0));
			if (DevelopmentEvidenceDecision.isClearlyWeak(counts, false, contract.developmentReadiness()))
				failing.add(category.id());
		}
		return List.copyOf(failing);
	}

	/**
	 * Names the first weak-category round for one incumbent and measurement identity.
	 *
	 * @param lineage confirmed lineage
	 * @param epochRef comparable measurement conditions
	 * @param candidateId incumbent snapshot
	 * @return stable create-once index key
	 */
	private static String key(LineageKey lineage, EvidenceRef epochRef, String candidateId)
	{
		return "initial-development-round-v1:" + lineage.lineageId() + ':' + epochRef.evidenceId() + ':' +
			candidateId;
	}
}
