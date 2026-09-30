package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests fixed-sample statistical decisions.
 */
public final class StatisticalDecisionTest
{
	/**
	 * Creates a test instance.
	 */
	public StatisticalDecisionTest()
	{
	}

	/**
	 * Verifies the lineage allowance begins with the documented exact values.
	 */
	@Test
	public void computesDocumentedLineageAllowances()
	{
		requireThat(LineageAllowance.forCandidate(1), "firstAllowance").isEqualTo(rational(1, 40));
		requireThat(LineageAllowance.forCandidate(2), "secondAllowance").isEqualTo(rational(1, 120));
		requireThat(LineageAllowance.forCandidate(3), "thirdAllowance").isEqualTo(rational(1, 240));
	}

	/**
	 * Verifies finite prefixes of the allowance sequence cannot exceed the lineage budget.
	 */
	@Test
	public void boundsLineageAllowancePrefixes()
	{
		RationalProbability allowanceSum = probability(0, 1);
		for (int candidate = 1; candidate <= 1_000; ++candidate)
		{
			allowanceSum = allowanceSum.add(LineageAllowance.forCandidate(candidate));
			requireThat(allowanceSum, "allowanceSum").isEqualTo(probability(candidate, 20L * (candidate + 1L)));
		}
	}

	/**
	 * Verifies candidate indices must begin at one.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNonpositiveCandidateIndices()
	{
		LineageAllowance.forCandidate(0);
	}

	/**
	 * Verifies the all-pass plans have the documented smallest sample sizes.
	 */
	@Test
	public void selectsSmallestAllPassSampleSizes()
	{
		requireThat(FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(1), probability(19, 20)).sampleSize(),
			"firstSampleSize").
			isEqualTo(72);
		requireThat(FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(2), probability(19, 20)).sampleSize(),
			"secondSampleSize").
			isEqualTo(94);
		requireThat(FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(3), probability(19, 20)).sampleSize(),
			"thirdSampleSize").
			isEqualTo(107);
	}

	/**
	 * Verifies the fixed plan meets the stated significance and power rule while allowing failures.
	 */
	@Test
	public void selectsFailureTolerantPowerPlan()
	{
		FixedSamplePlan first = FixedSamplePlan.forPower(LineageAllowance.forCandidate(1),
			probability(19, 20), probability(99, 100), probability(9, 10));
		FixedSamplePlan second = FixedSamplePlan.forPower(LineageAllowance.forCandidate(2),
			probability(19, 20), probability(99, 100), probability(9, 10));
		FixedSamplePlan third = FixedSamplePlan.forPower(LineageAllowance.forCandidate(3),
			probability(19, 20), probability(99, 100), probability(9, 10));

		requireThat(first.sampleSize(), "firstSampleSize").isEqualTo(173);
		requireThat(second.sampleSize(), "secondSampleSize").isEqualTo(234);
		requireThat(third.sampleSize(), "thirdSampleSize").isEqualTo(284);
		requireThat(first.successCutoff(), "firstSuccessCutoff").isEqualTo(170);
		requireThat(second.successCutoff(), "secondSuccessCutoff").isEqualTo(230);
		requireThat(third.successCutoff(), "thirdSuccessCutoff").isEqualTo(279);
		requireThat(StatisticalDecision.evaluate(first, Set.of("only"),
			Map.of("only", new CategoryCounts(170, 173))).status(),
			"atCutoff").isEqualTo(StatisticalStatus.THRESHOLD_MET);
		requireThat(StatisticalDecision.evaluate(first, Set.of("only"),
			Map.of("only", new CategoryCounts(169, 173))).status(),
			"belowCutoff").isEqualTo(StatisticalStatus.THRESHOLD_NOT_MET);
	}

	/**
	 * Verifies that power planning rejects a success rate that cannot distinguish compliance from the threshold.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNonDiscriminatingPowerInput()
	{
		FixedSamplePlan.forPower(LineageAllowance.forCandidate(1), probability(19, 20),
			probability(19, 20), probability(9, 10));
	}

	/**
	 * Verifies a weak category prevents a threshold result even when another category is perfect.
	 */
	@Test
	public void letsWeakCategoriesBlockStrongCategories()
	{
		FixedSamplePlan plan = FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(1), probability(19, 20));

		StatisticalDecision decision = StatisticalDecision.evaluate(plan, Set.of("strong", "weak"), Map.of(
			"strong", new CategoryCounts(plan.sampleSize(), plan.sampleSize()),
			"weak", new CategoryCounts(plan.sampleSize() - 1, plan.sampleSize())));

		requireThat(decision.status(), "status").isEqualTo(StatisticalStatus.THRESHOLD_NOT_MET);
		requireThat(decision.lowerBounds().keySet(), "categories").isEqualTo(Set.of("strong", "weak"));
	}

	/**
	 * Verifies every completed all-pass category produces a threshold result.
	 */
	@Test
	public void acceptsCompletedAllPassCategories()
	{
		FixedSamplePlan plan = FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(1), probability(19, 20));

		StatisticalDecision decision = StatisticalDecision.evaluate(plan, Set.of("first", "second"), Map.of(
			"first", new CategoryCounts(plan.sampleSize(), plan.sampleSize()),
			"second", new CategoryCounts(plan.sampleSize(), plan.sampleSize())));

		requireThat(decision.status(), "status").isEqualTo(StatisticalStatus.THRESHOLD_MET);
		for (double lowerBound : decision.lowerBounds().values())
			requireThat(lowerBound, "lowerBound").isGreaterThan(0.95);
	}

	/**
	 * Verifies that omitted confirmed categories cannot disappear from a certification decision.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMissingConfirmedCategory()
	{
		FixedSamplePlan plan = FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(1), probability(19, 20));
		StatisticalDecision.evaluate(plan, Set.of("first", "second"), Map.of(
			"first", new CategoryCounts(plan.sampleSize(), plan.sampleSize())));
	}

	/**
	 * Verifies that an outcome for an unconfirmed category cannot enter the certification decision.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsUnconfirmedCategory()
	{
		FixedSamplePlan plan = FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(1), probability(19, 20));
		StatisticalDecision.evaluate(plan, Set.of("first"), Map.of(
			"first", new CategoryCounts(plan.sampleSize(), plan.sampleSize()),
			"second", new CategoryCounts(plan.sampleSize(), plan.sampleSize())));
	}

	/**
	 * Verifies a bound equal to the threshold is not a passing result.
	 */
	@Test
	public void rejectsBoundsEqualToThreshold()
	{
		FixedSamplePlan plan = new FixedSamplePlan(probability(19, 20), probability(19, 20), 1, 2);

		StatisticalDecision decision = StatisticalDecision.evaluate(plan, Set.of("only"), Map.of(
			"only", new CategoryCounts(1, 1)));

		requireThat(decision.status(), "status").isEqualTo(StatisticalStatus.THRESHOLD_NOT_MET);
	}

	/**
	 * Verifies an interior root exactly at the threshold cannot pass certification arithmetic.
	 */
	@Test
	public void rejectsExactInteriorEquality()
	{
		FixedSamplePlan plan = new FixedSamplePlan(probability(7_999, 8_000), probability(19, 20), 3, 2);
		StatisticalDecision decision = StatisticalDecision.evaluate(plan, Set.of("only"), Map.of(
			"only", new CategoryCounts(1, 3)));
		requireThat(decision.status(), "status").isEqualTo(StatisticalStatus.THRESHOLD_NOT_MET);
		requireThat(decision.lowerBounds().get("only"), "bound").isEqualTo(0.95);
	}

	/**
	 * Verifies every prefix before the fixed sample size is incomplete, even when all observed trials pass.
	 */
	@Test
	public void keepsEveryPartialPrefixIncomplete()
	{
		FixedSamplePlan plan = FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(1), probability(19, 20));
		for (int trials = 0; trials < plan.sampleSize(); ++trials)
		{
			StatisticalDecision decision = StatisticalDecision.evaluate(plan, Set.of("only"), Map.of(
				"only", new CategoryCounts(trials, trials)));
			requireThat(decision.status(), "status").isEqualTo(StatisticalStatus.INCOMPLETE);
		}
	}

	/**
	 * Verifies repeated inspection cannot produce an early threshold result.
	 */
	@Test
	public void neverMakesAnEarlyDecisionAtRepeatedLooks()
	{
		FixedSamplePlan plan = FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(1), probability(19, 20));
		for (int trials = 1; trials < plan.sampleSize(); ++trials)
		{
			StatisticalDecision decision = StatisticalDecision.evaluate(plan, Set.of("first", "second"), Map.of(
				"first", new CategoryCounts(trials, trials),
				"second", new CategoryCounts(trials, trials)));
			requireThat(decision.status(), "status").isEqualTo(StatisticalStatus.INCOMPLETE);
		}
	}

	/**
	 * Verifies malformed inputs cannot produce a statistical decision.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsCountsAboveTheFixedSampleSize()
	{
		FixedSamplePlan plan = FixedSamplePlan.forAllPass(LineageAllowance.forCandidate(1), probability(19, 20));
		StatisticalDecision.evaluate(plan, Set.of("only"), Map.of("only", new CategoryCounts(plan.sampleSize() + 1,
			plan.sampleSize() + 1)));
	}

	/**
	 * Verifies invalid category counts are rejected before a decision is evaluated.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvalidCategoryCounts()
	{
		new CategoryCounts(1, 0);
	}

	/**
	 * Returns a reduced rational number for a test fixture.
	 *
	 * @param numerator the fixture numerator
	 * @param denominator the fixture denominator
	 * @return the fixture rational number
	 */
	private static RationalProbability rational(long numerator, long denominator)
	{
		return probability(numerator, denominator);
	}

	/**
	 * Returns an exact probability for a test fixture.
	 *
	 * @param numerator the fixture numerator
	 * @param denominator the fixture denominator
	 * @return the fixture probability
	 */
	private static RationalProbability probability(long numerator, long denominator)
	{
		return new RationalProbability(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
	}
}
