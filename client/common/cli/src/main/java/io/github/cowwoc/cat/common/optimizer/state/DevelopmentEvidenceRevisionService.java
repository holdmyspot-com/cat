package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorLabel;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes and verifies the counted-outcome revision cited by development allocation.
 */
final class DevelopmentEvidenceRevisionService
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CampaignStore store;

	/**
	 * Binds revision evidence to one pinned campaign.
	 *
	 * @param store pinned campaign store
	 */
	DevelopmentEvidenceRevisionService(CampaignStore store)
	{
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Publishes the exact current-candidate outcome prefix from a validated campaign read.
	 *
	 * @param evidence current representative evidence read from this campaign store
	 * @return immutable source revision
	 * @throws IOException if campaign or counted evidence cannot be validated
	 */
	EvidenceRef publish(DevelopmentEvidenceService.Snapshot evidence) throws IOException
	{
		Objects.requireNonNull(evidence, "evidence");
		Campaign campaign = evidence.campaign();
		if (campaign.status() != CampaignStatus.DEVELOPING || campaign.currentCandidateId() == null ||
			campaign.measurementState() == null || campaign.measurementState().pause().isPresent())
			throw new IOException("Development revision requires an active unpaused candidate");
		LineageSummary active = campaign.lineages().stream().filter(lineage ->
			lineage.lineageId().equals(campaign.activeLineageId())).findFirst().orElseThrow(() ->
			new IOException("Development revision has no active confirmed lineage"));
		LineageKey lineage = new LineageKey(active.lineageId(), active.confirmedContractId());
		EvidenceRef epochRef = campaign.measurementState().epochRef();
		List<EvidenceRef> outcomes = counted(campaign, lineage, campaign.currentCandidateId(), epochRef,
			campaign.transitionCounter());
		if (outcomes.isEmpty())
			throw new IOException("Development revision requires counted representative outcomes");
		DevelopmentEvidenceRevision revision = new DevelopmentEvidenceRevision(
			DevelopmentEvidenceRevision.SCHEMA_VERSION, campaign.campaignId(), lineage,
			campaign.currentCandidateId(), epochRef, campaign.transitionCounter(), outcomes);
		return store.appendImmutableJson("development-evidence-revisions", JSON.writeValueAsBytes(revision));
	}

	/**
	 * Checks a retained revision against a fresh campaign read after reopening the store.
	 *
	 * @param reference immutable revision reference
	 * @return validated revision
	 * @throws IOException if the revision or counted prefix is missing or contradictory
	 */
	DevelopmentEvidenceRevision read(EvidenceRef reference) throws IOException
	{
		return read(reference, store.load());
	}

	/**
	 * Checks a retained revision against an already validated campaign snapshot.
	 *
	 * @param reference immutable revision reference
	 * @param campaign campaign already validated by this store
	 * @return validated revision
	 * @throws IOException if the revision or counted prefix is missing or contradictory
	 */
	DevelopmentEvidenceRevision read(EvidenceRef reference, Campaign campaign) throws IOException
	{
		Objects.requireNonNull(reference, "reference");
		Objects.requireNonNull(campaign, "campaign");
		if (!reference.collection().equals("development-evidence-revisions"))
			throw new IOException("Development revision has the wrong evidence collection");
		byte[] bytes = store.readImmutableJson(reference);
		DevelopmentEvidenceRevision revision;
		try
		{
			revision = JSON.readValue(bytes, DevelopmentEvidenceRevision.class);
			if (revision == null || !Arrays.equals(bytes, JSON.writeValueAsBytes(revision)))
				throw new IOException("Development revision is noncanonical");
		}
		catch (RuntimeException exception)
		{
			throw new IOException("Development revision is malformed", exception);
		}
		if (!revision.campaignId().equals(campaign.campaignId()) ||
			revision.sourceCounter() > campaign.transitionCounter() ||
			campaign.lineages().stream().noneMatch(lineage ->
				lineage.lineageId().equals(revision.lineage().lineageId()) &&
				lineage.confirmedContractId().equals(revision.lineage().contractId())) ||
			!revision.outcomeRefs().equals(counted(campaign, revision.lineage(), revision.candidateId(),
				revision.measurementEpochRef(), revision.sourceCounter())))
			throw new IOException("Development revision conflicts with counted campaign outcomes");
		return revision;
	}

	/**
	 * Counts one category's labels from a validated outcome prefix.
	 *
	 * @param source validated representative outcome revision
	 * @param categoryId confirmed category to count
	 * @return compliant and total outcomes in that category
	 * @throws IOException if a retained outcome or assignment cannot be read
	 */
	CategoryCounts counts(DevelopmentEvidenceRevision source, String categoryId) throws IOException
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(categoryId, "categoryId");
		return counts(source).getOrDefault(categoryId, new CategoryCounts(0, 0));
	}

	/**
	 * Counts every category in one pass through the validated revision's outcome records.
	 *
	 * @param source validated representative outcome revision
	 * @return counts for categories present in the revision
	 * @throws IOException if a retained outcome or assignment cannot be read
	 */
	Map<String, CategoryCounts> counts(DevelopmentEvidenceRevision source) throws IOException
	{
		Objects.requireNonNull(source, "source");
		Map<String, CategoryCounts> result = new LinkedHashMap<>();
		for (EvidenceRef outcomeRef : source.outcomeRefs())
		{
			TrialOutcomeRecord outcome = store.readTrialOutcome(outcomeRef);
			TrialAssignment assignment = store.readTrialAssignment(outcome.assignmentRef());
			String categoryId = assignment.instance().definition().categoryId();
			CategoryCounts previous = result.getOrDefault(categoryId, new CategoryCounts(0, 0));
			int compliant = previous.successes();
			if (outcome.response().label() == EvaluatorLabel.COMPLIANT)
				compliant = Math.incrementExact(compliant);
			result.put(categoryId, new CategoryCounts(compliant, Math.incrementExact(previous.trials())));
		}
		return Map.copyOf(result);
	}

	/**
	 * Lists only semantic outcomes counted for one candidate and epoch through a retained counter.
	 *
	 * @param campaign verified campaign
	 * @param lineage confirmed lineage
	 * @param candidateId selected candidate
	 * @param epochRef selected measurement epoch
	 * @param sourceCounter last visible campaign transition
	 * @return matching outcome references in transition order
	 * @throws IOException if an outcome or assignment cannot be read
	 */
	private List<EvidenceRef> counted(Campaign campaign, LineageKey lineage, String candidateId,
		EvidenceRef epochRef, long sourceCounter) throws IOException
	{
		List<EvidenceRef> result = new ArrayList<>();
		for (TransitionReceipt receipt : campaign.transitionReceipts())
		{
			if (receipt.resultingCounter() > sourceCounter || receipt.kind() != TransitionKind.SEMANTIC_OUTCOME)
				continue;
			TrialOutcomeRecord outcome = store.readTrialOutcome(receipt.producerRecordRef());
			TrialAssignment assignment = store.readTrialAssignment(outcome.assignmentRef());
			if (assignment.lineage().equals(lineage) && assignment.candidateId().equals(candidateId) &&
				assignment.measurementEpochRef().equals(epochRef))
				result.add(receipt.producerRecordRef());
		}
		return result;
	}
}
