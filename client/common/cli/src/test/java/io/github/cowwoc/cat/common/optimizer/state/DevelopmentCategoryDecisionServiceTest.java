package io.github.cowwoc.cat.common.optimizer.state;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ComparisonPolicy;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentAllocationPolicy;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentReadiness;
import io.github.cowwoc.cat.common.optimizer.contract.SessionMode;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.statistics.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.trial.SampledTrialInstance;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Separates revision boundaries from processing individual decoded batch assignments.
 */
public final class DevelopmentCategoryDecisionServiceTest
{
	private static final String DIGEST = "a".repeat(64);
	private static final LineageKey LINEAGE = new LineageKey("lineage", DIGEST);
	private static final EvidenceRef EPOCH = new EvidenceRef("measurement-epochs", DIGEST);
	private static final EvidenceRef PREVIOUS = new EvidenceRef("development-category-decisions", DIGEST);

	/** Creates the test instance. */
	public DevelopmentCategoryDecisionServiceTest()
	{
	}

	/** A third completed batch extends its category's total and history without changing another category. */
	@Test
	public void extendsCompletedBatchHistory()
	{
		CategoryCounts initial = new CategoryCounts(45, 50);
		CategoryCounts followup = new CategoryCounts(49, 50);
		CategoryCounts added = new CategoryCounts(50, 50);
		DevelopmentEvidenceService.CategorySeries first = new DevelopmentEvidenceService.CategorySeries(
			new CategoryCounts(94, 100), List.of(initial, followup));
		DevelopmentEvidenceService.CategorySeries second = new DevelopmentEvidenceService.CategorySeries(
			added, List.of(added));
		Map<String, DevelopmentEvidenceService.CategorySeries> previous = Map.of("first", first, "second", second);
		Map<String, DevelopmentEvidenceService.CategorySeries> extended =
			DevelopmentCategoryDecisionService.appendCompletedBatch(previous, "first", added);
		requireThat(extended.get("first"), "extendedHistory").isEqualTo(
			new DevelopmentEvidenceService.CategorySeries(new CategoryCounts(144, 150),
				List.of(initial, followup, added)));
		requireThat(extended.get("second"), "unchangedOtherCategory").isEqualTo(second);
		requireThat(previous.get("first"), "unchangedPrecedingHistory").isEqualTo(first);
	}

	/**
	 * An adaptive batch leaves the coverage cursor pointing to the next confirmed category.
	 *
	 * @throws IOException if the counted categories unexpectedly require a higher-priority decision
	 */
	@Test
	public void resumesCoverageAfterAdaptiveBatch() throws IOException
	{
		List<String> categories = List.of("first", "second");
		DevelopmentReadiness readiness = new DevelopmentReadiness(new BigDecimal("0.99"),
			Map.of("first", 100, "second", 100), new BigDecimal("0.98"), 2,
			new BigDecimal("0.95"), new BigDecimal("0.95"), 200,
			ComparisonPolicy.SORTED_READINESS_DEFICIT_THEN_PARETO);
		CategoryCounts initial = new CategoryCounts(45, 50);
		CategoryCounts followup = new CategoryCounts(49, 50);
		CategoryCounts compliant = new CategoryCounts(50, 50);
		DevelopmentEvidenceService.CategorySeries second = new DevelopmentEvidenceService.CategorySeries(
			compliant, List.of(compliant));
		DevelopmentBatchSelector.Selection coverage = DevelopmentCategoryDecisionService.select(readiness,
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE, categories,
			Map.of("first", new DevelopmentEvidenceService.CategorySeries(initial, List.of(initial)),
				"second", second),
			new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.COVERAGE, 0), Set.of());
		requireThat(coverage.categoryId(), "firstCoverageCategory").isEqualTo("first");
		DevelopmentBatchSelector.Selection adaptive = DevelopmentCategoryDecisionService.select(readiness,
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE, categories,
			Map.of("first", new DevelopmentEvidenceService.CategorySeries(new CategoryCounts(94, 100),
				List.of(initial, followup)), "second", second), coverage.next(), Set.of());
		requireThat(adaptive.categoryId(), "adaptiveCategory").isEqualTo("first");
		requireThat(adaptive.next(), "resumedCoveragePosition").isEqualTo(
			new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.COVERAGE, 1));
		DevelopmentBatchSelector.Selection resumed = DevelopmentCategoryDecisionService.select(readiness,
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE, categories,
			Map.of("first", new DevelopmentEvidenceService.CategorySeries(new CategoryCounts(144, 150),
				List.of(initial, followup, compliant)), "second", second), adaptive.next(), Set.of());
		requireThat(resumed.categoryId(), "resumedCoverageCategory").isEqualTo("second");
		requireThat(resumed.next(), "afterResumedCoverage").isEqualTo(
			new DevelopmentBatchSelector.Progress(DevelopmentBatchSelector.Turn.ADAPTIVE, 0));
	}

	/** Exactly 50 added references form a batch without opening or processing any outcome record. */
	@Test
	public void selectsCompletedBatchReferences() throws IOException
	{
		List<EvidenceRef> outcomes = references(51);
		DevelopmentEvidenceRevision previous = revision(outcomes.subList(0, 1), 1);
		DevelopmentEvidenceRevision current = revision(outcomes, 51);
		requireThat(DevelopmentCategoryDecisionService.completedBatchOutcomes(previous, current,
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE.batchSize()), "addedBatchReferences").
			isEqualTo(outcomes.subList(1, 51));
	}

	/** A partial or oversized suffix cannot authorize the next batch. */
	@Test
	public void rejectsIncompleteAndOversizedBatches()
	{
		List<EvidenceRef> outcomes = references(52);
		DevelopmentEvidenceRevision previous = revision(outcomes.subList(0, 1), 1);
		for (int added : List.of(49, 51))
		{
			DevelopmentEvidenceRevision current = revision(outcomes.subList(0, added + 1), added + 1);
			Assert.expectThrows(IOException.class, () -> DevelopmentCategoryDecisionService.completedBatchOutcomes(
				previous, current, DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE.batchSize()));
		}
	}

	/** A full suffix cannot hide replacement of a prior outcome or a nonadvancing counter. */
	@Test
	public void preservesPriorPrefixAndCounter()
	{
		List<EvidenceRef> outcomes = references(52);
		DevelopmentEvidenceRevision previous = revision(outcomes.subList(0, 1), 1);
		DevelopmentEvidenceRevision replaced = revision(outcomes.subList(1, 52), 52);
		Assert.expectThrows(IOException.class, () -> DevelopmentCategoryDecisionService.completedBatchOutcomes(
			previous, replaced, DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE.batchSize()));
		DevelopmentEvidenceRevision unchangedCounter = revision(outcomes.subList(0, 51), 1);
		Assert.expectThrows(IOException.class, () -> DevelopmentCategoryDecisionService.completedBatchOutcomes(
			previous, unchangedCounter, DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE.batchSize()));
	}

	/** One decoded assignment proves binding and ordinal registration without a campaign or native run. */
	@Test
	public void acceptsOnePrecedingBatchAssignment() throws IOException
	{
		Set<Integer> ordinals = new HashSet<>();
		DevelopmentCategoryDecisionService.requirePrecedingBatchAssignment(assignment(DIGEST, 0), PREVIOUS,
			decision(DevelopmentBatchSelector.Turn.COVERAGE),
			decision(DevelopmentBatchSelector.Turn.ADAPTIVE),
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE.batchSize(), ordinals);
		requireThat(ordinals, "acceptedOrdinal").isEqualTo(Set.of(0));
	}

	/** An assignment from another candidate cannot contribute an ordinal to the completed batch. */
	@Test
	public void rejectsForeignCandidateAssignment()
	{
		Set<Integer> ordinals = new HashSet<>();
		Assert.expectThrows(IOException.class, () -> DevelopmentCategoryDecisionService.requirePrecedingBatchAssignment(
			assignment("b".repeat(64), 0), PREVIOUS, decision(DevelopmentBatchSelector.Turn.COVERAGE),
			decision(DevelopmentBatchSelector.Turn.ADAPTIVE),
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE.batchSize(), ordinals));
		requireThat(ordinals, "noForeignOrdinal").isEmpty();
	}

	/** A repeated ordinal cannot count twice even when its assignment identity is valid. */
	@Test
	public void rejectsRepeatedAssignmentOrdinal() throws IOException
	{
		Set<Integer> ordinals = new HashSet<>();
		TrialAssignment assignment = assignment(DIGEST, 0);
		DevelopmentCategoryDecisionService.requirePrecedingBatchAssignment(assignment, PREVIOUS,
			decision(DevelopmentBatchSelector.Turn.COVERAGE), decision(DevelopmentBatchSelector.Turn.ADAPTIVE),
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE.batchSize(), ordinals);
		Assert.expectThrows(IOException.class, () -> DevelopmentCategoryDecisionService.requirePrecedingBatchAssignment(
			assignment, PREVIOUS, decision(DevelopmentBatchSelector.Turn.COVERAGE),
			decision(DevelopmentBatchSelector.Turn.ADAPTIVE),
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE.batchSize(), ordinals));
		requireThat(ordinals, "oneDistinctOrdinal").isEqualTo(Set.of(0));
	}

	/**
	 * Supplies identity-only references; the batch boundary must not need their contents.
	 *
	 * @param count number of distinct outcome references
	 * @return references without persisted records
	 */
	private static List<EvidenceRef> references(int count)
	{
		List<EvidenceRef> result = new ArrayList<>(count);
		for (int index = 0; index < count; ++index)
			result.add(new EvidenceRef("trial-outcomes", String.format("%064x", index)));
		return List.copyOf(result);
	}

	/**
	 * Supplies already validated revision metadata to the count and prefix boundary.
	 *
	 * @param outcomes ordered outcome references
	 * @param counter visible transition counter
	 * @return typed revision without processing outcome contents
	 */
	private static DevelopmentEvidenceRevision revision(List<EvidenceRef> outcomes, long counter)
	{
		return new DevelopmentEvidenceRevision(DevelopmentEvidenceRevision.SCHEMA_VERSION, "campaign", LINEAGE,
			DIGEST, EPOCH, counter, outcomes);
	}

	/**
	 * Supplies one alternating choice without replaying earlier batches.
	 *
	 * @param turn preceding coverage or next adaptive turn
	 * @return choice identity for assignment validation
	 */
	private static DevelopmentCategoryDecision decision(DevelopmentBatchSelector.Turn turn)
	{
		int batchNumber = switch (turn)
		{
			case COVERAGE -> 1;
			case ADAPTIVE -> 2;
		};
		DevelopmentBatchSelector.Turn next = switch (turn)
		{
			case COVERAGE -> DevelopmentBatchSelector.Turn.ADAPTIVE;
			case ADAPTIVE -> DevelopmentBatchSelector.Turn.COVERAGE;
		};
		return new DevelopmentCategoryDecision(DevelopmentCategoryDecision.SCHEMA_VERSION, "campaign", LINEAGE,
			DIGEST, EPOCH, new EvidenceRef("development-evidence-revisions", DIGEST),
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE, List.of("first"), List.of(), "first",
			EvidencePurpose.DEVELOPMENT, batchNumber, new DevelopmentBatchSelector.Progress(turn, 0),
			new DevelopmentBatchSelector.Progress(next, 0), List.of());
	}

	/**
	 * Supplies one decoded assignment, varying only the property needed by the identity claim.
	 *
	 * @param candidateId measured candidate
	 * @param ordinal ordinal within the batch
	 * @return typed assignment without native execution or evidence reads
	 */
	private static TrialAssignment assignment(String candidateId, int ordinal)
	{
		TestDefinition definition = new TestDefinition("test", "first", List.of("r1"), "Always", List.of("task"),
			"Comply", SessionMode.SINGLE_TURN, List.of(), List.of());
		SampledTrialInstance instance = new SampledTrialInstance("draw", EvidencePurpose.DEVELOPMENT, "sampler",
			DIGEST, definition, List.of(), definition.turnTemplates());
		return new TrialAssignment(TrialAssignment.SCHEMA_VERSION, "campaign", LINEAGE, EPOCH, PREVIOUS, "trial",
			ordinal, candidateId, instance, Instant.EPOCH);
	}
}
