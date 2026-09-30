package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorLabel;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads representative development assignments and outcomes for the active candidate and measurement epoch.
 */
final class DevelopmentEvidenceService
{
	/**
	 * One assigned trial and its semantic outcome, if one was retained.
	 *
	 * @param assignment retained representative draw
	 * @param trial current state of that logical draw
	 * @param outcome counted semantic outcome, or null when the draw has none
	 */
	record Slot(TrialAssignment assignment, TrialState trial, TrialOutcomeRecord outcome)
	{
		/**
		 * Binds the retained trial to its assigned draw and optional semantic outcome.
		 *
		 * @param assignment retained representative draw
		 * @param trial current state of that logical draw
		 * @param outcome counted semantic outcome, or null when the draw has none
		 */
		Slot
		{
			Objects.requireNonNull(assignment, "assignment");
			Objects.requireNonNull(trial, "trial");
			if (!trial.logicalTrialId().equals(assignment.logicalTrialId()) ||
				!trial.selectionEvidence().collection().equals("trial-assignments") ||
				semantic(trial.status()) != (outcome != null) ||
				outcome != null && !outcome.assignmentRef().equals(trial.selectionEvidence()))
				throw new IllegalArgumentException("Representative trial and semantic outcome disagree");
		}
	}

	/**
	 * Assignments made under one published category decision.
	 *
	 * @param decisionRef published decision cited by the assignments
	 * @param categoryId selected category
	 * @param slots assignments indexed by their ordinal within this batch
	 */
	record Batch(EvidenceRef decisionRef, String categoryId, Map<Integer, Slot> slots)
	{
		/**
		 * Copies the batch's assigned ordinals and checks their common choice.
		 *
		 * @param decisionRef published decision cited by the assignments
		 * @param categoryId selected category
		 * @param slots assignments indexed by their ordinal within this batch
		 */
		Batch
		{
			Objects.requireNonNull(decisionRef, "decisionRef");
			Objects.requireNonNull(categoryId, "categoryId");
			slots = Map.copyOf(slots);
			if (categoryId.isBlank() || slots.entrySet().stream().anyMatch(entry ->
				entry.getKey() != entry.getValue().assignment().ordinal() ||
				!entry.getValue().assignment().categoryDecisionRef().equals(decisionRef) ||
				!entry.getValue().assignment().instance().definition().categoryId().equals(categoryId)))
				throw new IllegalArgumentException("Representative batch contains a foreign assignment");
		}

		/**
		 * Counts retained semantic outcomes without counting pending or infrastructure trials.
		 *
		 * @return compliant and total semantic outcomes
		 */
		CategoryCounts counts()
		{
			int successes = 0;
			int trials = 0;
			for (Slot slot : slots.values())
			{
				if (slot.outcome() == null)
					continue;
				trials = Math.incrementExact(trials);
				if (slot.outcome().response().label() == EvaluatorLabel.COMPLIANT)
					successes = Math.incrementExact(successes);
			}
			return new CategoryCounts(successes, trials);
		}
	}

	/**
	 * Outcomes for one category in the current candidate and measurement epoch.
	 *
	 * @param total every counted outcome, including an unfinished batch
	 * @param completedBatches fully counted batches in assignment order
	 */
	record CategorySeries(CategoryCounts total, List<CategoryCounts> completedBatches)
	{
		/**
		 * Copies completed batches in their original assignment order.
		 *
		 * @param total every counted outcome, including an unfinished batch
		 * @param completedBatches fully counted batches in assignment order
		 */
		CategorySeries
		{
			Objects.requireNonNull(total, "total");
			completedBatches = List.copyOf(completedBatches);
		}
	}

	/**
	 * Current campaign and its source-backed representative batches.
	 *
	 * @param campaign validated campaign state
	 * @param batches batches in first-assignment order
	 */
	record Snapshot(Campaign campaign, List<Batch> batches)
	{
		/**
		 * Copies the batches found in the validated campaign.
		 *
		 * @param campaign validated campaign state
		 * @param batches batches in first-assignment order
		 */
		Snapshot
		{
			Objects.requireNonNull(campaign, "campaign");
			batches = List.copyOf(batches);
		}

		/**
		 * Groups current-epoch representative outcomes by category and completed batch.
		 *
		 * @param batchSize confirmed number of outcomes required to complete a batch
		 * @return counted category totals and completed batches
		 */
		Map<String, CategorySeries> categorySeries(int batchSize)
		{
			if (batchSize <= 0)
				throw new IllegalArgumentException("Batch size must be positive");
			Map<String, CategoryCounts> totals = new LinkedHashMap<>();
			Map<String, List<CategoryCounts>> completed = new LinkedHashMap<>();
			for (Batch batch : batches)
			{
				if (batch.slots().size() > batchSize)
					throw new IllegalArgumentException("Representative batch exceeds the confirmed batch size");
				CategoryCounts counts = batch.counts();
				totals.merge(batch.categoryId(), counts, (earlier, later) -> new CategoryCounts(
					Math.addExact(earlier.successes(), later.successes()),
					Math.addExact(earlier.trials(), later.trials())));
				if (counts.trials() == batchSize)
					completed.computeIfAbsent(batch.categoryId(), ignored -> new ArrayList<>()).add(counts);
			}
			Map<String, CategorySeries> result = new LinkedHashMap<>();
			for (Map.Entry<String, CategoryCounts> entry : totals.entrySet())
				result.put(entry.getKey(), new CategorySeries(entry.getValue(),
					completed.getOrDefault(entry.getKey(), List.of())));
			return Map.copyOf(result);
		}
	}

	private final CampaignStore store;

	/**
	 * Binds evidence reads to one pinned campaign.
	 *
	 * @param store pinned campaign store
	 */
	DevelopmentEvidenceService(CampaignStore store)
	{
		this.store = Objects.requireNonNull(store, "store");
	}

	/**
	 * Reopens retained evidence and groups current-candidate draws by their published choice.
	 *
	 * @return the validated campaign and representative batches
	 * @throws IOException if a retained assignment or outcome cannot be validated
	 */
	Snapshot readCurrent() throws IOException
	{
		return groupTrialEvidence(store.readTrialEvidence());
	}

	/**
	 * Groups the decoded trial records from this store's immediate campaign validation.
	 *
	 * @param read exact result of the caller's current campaign read
	 * @return representative batches without reopening records already validated by that read
	 * @throws IOException if the read belongs to another store or a fallback record is invalid
	 */
	Snapshot groupTrialEvidence(CampaignStore.TrialEvidenceRead read) throws IOException
	{
		Objects.requireNonNull(read, "read");
		if (!read.binding().realDirectory().equals(store.directory()))
			throw new IOException("Representative evidence belongs to a different campaign store");
		return readCurrent(read.campaign(), read.assignments(), read.outcomes());
	}

	/**
	 * Groups representative evidence from a campaign already read under its state lock.
	 *
	 * @param campaign validated campaign snapshot
	 * @return representative batches for its current candidate and measurement epoch
	 * @throws IOException if an assignment or outcome cannot be validated
	 */
	Snapshot readCurrent(Campaign campaign) throws IOException
	{
		return readCurrent(campaign, Map.of(), Map.of());
	}

	/**
	 * Groups evidence using exact records from this campaign read, reopening any record absent from it.
	 *
	 * @param campaign validated campaign snapshot
	 * @param assignments assignment records decoded while validating this snapshot
	 * @param outcomes outcome records decoded while validating this snapshot
	 * @return grouped current-candidate evidence
	 * @throws IOException if a fallback record or grouped batch is invalid
	 */
	private Snapshot readCurrent(Campaign campaign, Map<EvidenceRef, TrialAssignment> assignments,
		Map<EvidenceRef, TrialOutcomeRecord> outcomes) throws IOException
	{
		Objects.requireNonNull(campaign, "campaign");
		if (campaign.currentCandidateId() == null || campaign.measurementState() == null)
			throw new IOException("Representative evidence requires a current candidate and measurement epoch");
		EvidenceRef epochRef = campaign.measurementState().epochRef();
		Map<EvidenceRef, Map<Integer, Slot>> grouped = new LinkedHashMap<>();
		Map<EvidenceRef, String> categories = new LinkedHashMap<>();
		for (TrialState trial : campaign.trials())
		{
			if (trial.purpose() != EvidencePurpose.DEVELOPMENT ||
				!trial.candidateId().equals(campaign.currentCandidateId()) ||
				!trial.selectionEvidence().collection().equals("trial-assignments"))
				continue;
			TrialAssignment assignment = assignments.get(trial.selectionEvidence());
			if (assignment == null)
				assignment = store.readTrialAssignment(trial.selectionEvidence());
			if (!assignment.measurementEpochRef().equals(epochRef))
				continue;
			TrialOutcomeRecord outcome = null;
			if (semantic(trial.status()))
			{
				outcome = outcomes.get(trial.outcomeEvidence());
				if (outcome == null)
					outcome = store.readTrialOutcome(trial.outcomeEvidence());
			}
			EvidenceRef decisionRef = assignment.categoryDecisionRef();
			String categoryId = assignment.instance().definition().categoryId();
			String previousCategory = categories.putIfAbsent(decisionRef, categoryId);
			if (previousCategory != null && !previousCategory.equals(categoryId))
				throw new IOException("Published category decision has assignments from different categories");
			Map<Integer, Slot> slots = grouped.computeIfAbsent(decisionRef, ignored -> new LinkedHashMap<>());
			if (slots.putIfAbsent(assignment.ordinal(), new Slot(assignment, trial, outcome)) != null)
				throw new IOException("Published category decision has duplicate assignment ordinals");
		}
		List<Batch> batches = new ArrayList<>(grouped.size());
		for (Map.Entry<EvidenceRef, Map<Integer, Slot>> entry : grouped.entrySet())
			batches.add(new Batch(entry.getKey(), categories.get(entry.getKey()), entry.getValue()));
		return new Snapshot(campaign, batches);
	}

	/**
	 * Indicates whether the trial state has a counted semantic outcome.
	 *
	 * @param status retained trial status
	 * @return true for a compliant or noncompliant semantic result
	 */
	private static boolean semantic(TrialStatus status)
	{
		return switch (status)
		{
			case COMPLIANT -> true;
			case NONCOMPLIANT -> true;
			default -> false;
		};
	}
}
