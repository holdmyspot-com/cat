package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentEvidenceDecision.Status.READY;
import static io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentEvidenceDecision.Status.REVIEW_REQUIRED;
import static io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentEvidenceDecision.Status.UNCERTAIN;
import static io.github.cowwoc.cat.common.optimizer.statistics.DevelopmentEvidenceDecision.Status.WEAK;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ComparisonPolicy;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentAllocationPolicy;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentReadiness;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Checks category decisions made from representative development evidence.
 */
public final class DevelopmentEvidenceDecisionTest
{
	/**
	 * Creates the test fixture.
	 */
	public DevelopmentEvidenceDecisionTest()
	{
	}

	/**
	 * A reproducible requirement failure is actionable before a batch or minimum sample count is complete.
	 */
	@Test
	public void classifiesRepeatableDefectImmediately()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(0, 0, List.of(), true, false);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").isEqualTo(WEAK);
	}

	/**
	 * The confirmed Wilson upper endpoint can expose a weak category without a readiness-sized sample.
	 */
	@Test
	public void classifiesStatisticallyWeakCategory()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(30, 50,
			List.of(new CategoryCounts(30, 50)), false, false);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").isEqualTo(WEAK);
	}

	/**
	 * After 49 successes in an initial batch, the default weakness threshold stops sampling at five more failures.
	 */
	@Test
	public void stopsAtDefaultFollowupWeaknessBoundary()
	{
		DevelopmentReadiness policy = readiness(new BigDecimal("0.95"));
		requireThat(classify(evidence(49, 54, List.of(new CategoryCounts(49, 50)), false, false), policy),
			"beforeWeakness").isEqualTo(UNCERTAIN);
		requireThat(classify(evidence(49, 55, List.of(new CategoryCounts(49, 50)), false, false), policy),
			"atWeakness").isEqualTo(WEAK);
	}

	/**
	 * A 99-of-100 candidate with two stable batches is ready despite a formal-style lower bound below 95%.
	 */
	@Test
	public void classifiesReadyWithoutFormalBound()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(99, 100,
			List.of(new CategoryCounts(49, 50), new CategoryCounts(50, 50)), false, false);
		requireThat(DevelopmentWilsonInterval.calculate(evidence.total()).getLowerBound(), "exploratoryLower").
			isLessThan(0.95);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").isEqualTo(READY);
	}

	/**
	 * The latest completed batches control stability; a 200-trial unresolved result asks for review.
	 */
	@Test
	public void requiresReviewAtCheckpoint()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(198, 200,
			List.of(new CategoryCounts(50, 50), new CategoryCounts(50, 50), new CategoryCounts(48, 50),
				new CategoryCounts(50, 50)), false, false);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").
			isEqualTo(REVIEW_REQUIRED);
	}

	/**
	 * An unresolved category requires review at 200 counted outcomes, but not at 199.
	 */
	@Test
	public void requiresReviewAtDefaultCountBoundary()
	{
		DevelopmentReadiness policy = readiness(new BigDecimal("0.95"));
		requireThat(classify(evidence(190, 199, List.of(), false, false), policy), "beforeCheckpoint").
			isEqualTo(UNCERTAIN);
		requireThat(classify(evidence(191, 200, List.of(), false, false), policy), "atCheckpoint").
			isEqualTo(REVIEW_REQUIRED);
	}

	/**
	 * At 250 outcomes, the category remains unresolved and needs a review.
	 */
	@Test
	public void requiresReviewAfterMoreEvidence()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(241, 250,
			List.of(new CategoryCounts(45, 50), new CategoryCounts(49, 50), new CategoryCounts(50, 50),
				new CategoryCounts(47, 50), new CategoryCounts(50, 50)), false, false);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").
			isEqualTo(REVIEW_REQUIRED);
	}

	/**
	 * A previously weak batch stops affecting stability after two newer complete stable batches.
	 */
	@Test
	public void usesLatestTwoCompletedBatches()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(248, 250,
			List.of(new CategoryCounts(50, 50), new CategoryCounts(50, 50), new CategoryCounts(48, 50),
				new CategoryCounts(50, 50), new CategoryCounts(50, 50)), false, false);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").isEqualTo(READY);
	}

	/**
	 * An unfinished batch contributes to the interval while only completed batches satisfy stability.
	 */
	@Test
	public void countsOutcomesFromUnfinishedBatch()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(49, 75,
			List.of(new CategoryCounts(49, 50)), false, false);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").isEqualTo(WEAK);
	}

	/**
	 * A revised requirement for three stable batches cannot be satisfied by two completed batches.
	 */
	@Test
	public void appliesRevisedStableBatchCount()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(99, 100,
			List.of(new CategoryCounts(49, 50), new CategoryCounts(50, 50)), false, false);
		DevelopmentReadiness policy = new DevelopmentReadiness(new BigDecimal("0.99"),
			Map.of("category", 100), new BigDecimal("0.98"), 3, new BigDecimal("0.95"),
			new BigDecimal("0.95"), 200, ComparisonPolicy.SORTED_READINESS_DEFICIT_THEN_PARETO);
		requireThat(classify(evidence, policy), "decision").isEqualTo(UNCERTAIN);
	}

	/**
	 * A revised weak-evidence confidence level changes the decision made from the same representative outcomes.
	 */
	@Test
	public void appliesConfirmedWeakIntervalLevel()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(91, 100,
			List.of(new CategoryCounts(45, 50), new CategoryCounts(46, 50)), false, false);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "defaultDecision").
			isEqualTo(UNCERTAIN);
		requireThat(classify(evidence, readiness(new BigDecimal("0.90"))), "revisedDecision").isEqualTo(WEAK);
	}

	/**
	 * An upper endpoint equal to the confirmed weak threshold remains uncertain because weakness requires below.
	 */
	@Test
	public void keepsEqualWeakThresholdUncertain()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(91, 100,
			List.of(new CategoryCounts(45, 50), new CategoryCounts(46, 50)), false, false);
		BigDecimal threshold = BigDecimal.valueOf(DevelopmentWilsonInterval.calculate(evidence.total()).
			getUpperBound());
		DevelopmentReadiness policy = new DevelopmentReadiness(BigDecimal.ONE, Map.of("category", 100),
			new BigDecimal("0.98"), 2, threshold, new BigDecimal("0.95"), 200,
			ComparisonPolicy.SORTED_READINESS_DEFICIT_THEN_PARETO);
		requireThat(classify(evidence, policy), "decision").isEqualTo(UNCERTAIN);
	}

	/**
	 * A confirmed threshold just above the computed upper endpoint remains distinct after numeric conversion.
	 */
	@Test
	public void honorsPreciseWeakThreshold()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(91, 100,
			List.of(new CategoryCounts(45, 50), new CategoryCounts(46, 50)), false, false);
		double upperEndpoint = DevelopmentWilsonInterval.calculate(evidence.total()).getUpperBound();
		BigDecimal threshold = BigDecimal.valueOf(upperEndpoint).add(new BigDecimal("0.000000000000000001"));
		requireThat(threshold.doubleValue(), "roundedThreshold").isEqualTo(upperEndpoint);
		DevelopmentReadiness policy = new DevelopmentReadiness(BigDecimal.ONE, Map.of("category", 100),
			new BigDecimal("0.98"), 2, threshold, new BigDecimal("0.95"), 200,
			ComparisonPolicy.SORTED_READINESS_DEFICIT_THEN_PARETO);
		requireThat(classify(evidence, policy), "decision").isEqualTo(WEAK);
	}

	/**
	 * A known regression remains actionable even when the representative counts satisfy readiness thresholds.
	 */
	@Test
	public void identifiesKnownRegression()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(100, 100,
			List.of(new CategoryCounts(50, 50), new CategoryCounts(50, 50)), false, true);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").
			isEqualTo(DevelopmentEvidenceDecision.Status.REGRESSION);
	}

	/**
	 * Completed batches cannot omit a failure when their trial counts already equal the representative total.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsContradictoryBatchSuccesses()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(100, 100,
			List.of(new CategoryCounts(49, 50), new CategoryCounts(50, 50)), false, false);
		classify(evidence, readiness(new BigDecimal("0.95")));
	}

	/**
	 * Additional representative outcomes do not imply that another batch has completed.
	 */
	@Test
	public void usesRecordedCompletedBatches()
	{
		DevelopmentEvidenceDecision.CategoryEvidence evidence = evidence(198, 200,
			List.of(new CategoryCounts(50, 50), new CategoryCounts(50, 50)), false, false);
		requireThat(classify(evidence, readiness(new BigDecimal("0.95"))), "decision").isEqualTo(READY);
	}

	/**
	 * Creates test-owned representative totals and completed batches.
	 *
	 * @param successes compliant outcomes
	 * @param trials completed outcomes
	 * @param batches completed batches in execution order
	 * @param defect whether a requirement failure is reproducible
	 * @param regression whether a previously passing requirement regressed
	 * @return category evidence to classify
	 */
	private static DevelopmentEvidenceDecision.CategoryEvidence evidence(int successes, int trials,
		List<CategoryCounts> batches, boolean defect, boolean regression)
	{
		return new DevelopmentEvidenceDecision.CategoryEvidence(new CategoryCounts(successes, trials), batches,
			defect, regression);
	}

	/**
	 * Creates the accepted readiness defaults with a selectable weak-interval confidence level.
	 *
	 * @param confidenceLevel two-sided exploratory coverage level
	 * @return confirmed development policy
	 */
	private static DevelopmentReadiness readiness(BigDecimal confidenceLevel)
	{
		return new DevelopmentReadiness(new BigDecimal("0.99"), Map.of("category", 100),
			new BigDecimal("0.98"), 2, new BigDecimal("0.95"), confidenceLevel, 200,
			ComparisonPolicy.SORTED_READINESS_DEFICIT_THEN_PARETO);
	}

	/**
	 * Classifies the test category using the accepted 50-trial batch policy.
	 *
	 * @param evidence current-candidate representative evidence
	 * @param readiness confirmed thresholds
	 * @return decision for the category
	 */
	private static DevelopmentEvidenceDecision.Status classify(DevelopmentEvidenceDecision.CategoryEvidence evidence,
		DevelopmentReadiness readiness)
	{
		return DevelopmentEvidenceDecision.classify("category", evidence, readiness,
			DevelopmentAllocationPolicy.BALANCED_COVERAGE_ADAPTIVE);
	}
}
