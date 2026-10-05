package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.REQUIRED_PASS_RATE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.ABOVE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.BELOW;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tests {@link DecisionBoundaries} and {@link ComparisonBoundaries}.
 * <p>
 * The tests check these reference values. The other boundary pairs come from an independent script that
 * compares exact rationals.
 * <table>
 *   <caption>Reference values checked by this class</caption>
 *   <tr><th>Quantity</th><th>Reference value</th><th>Checked value</th></tr>
 *   <tr><td>attempt 1's all-pass minimum</td><td>93</td><td>93</td></tr>
 *   <tr><td>attempt 2's all-pass minimum</td><td>117</td><td>117</td></tr>
 *   <tr><td>attempt 3's all-pass minimum</td><td>132</td><td>132</td></tr>
 *   <tr><td>all-pass trials proving improvement over {@code UI = 0.90} with 3 cells</td>
 *     <td>79</td><td>79</td></tr>
 * </table>
 */
public final class DecisionBoundariesTest
{
	/**
	 * Creates a test instance.
	 */
	public DecisionBoundariesTest()
	{
	}

	/**
	 * Verifies that the first attempt's table holds the certification and clearly-below boundary pairs.
	 */
	@Test
	public void firstAttemptTableHoldsBoundaries()
	{
		// Requirement: R-S2, R-S3
		DecisionBoundaries boundaries = FirstAttemptBoundaries.get();
		requireThat(boundaries.cap(), "cap").isEqualTo(262);
		requireThat(boundaries.minimumCertifyingPasses().subList(0, 3), "minimumCertifyingPasses").
			isEqualTo(List.of(93, 147, 184));
		requireThat(boundaries.maximumClearlyBelowPasses().get(3), "maximumClearlyBelowF3").isEqualTo(3);
		requireThat(boundaries.maximumClearlyBelowPasses().get(5), "maximumClearlyBelowF5").isEqualTo(12);
		requireThat(boundaries.maximumClearlyBelowPasses().get(10), "maximumClearlyBelowF10").isEqualTo(54);
		requireThat(boundaries.certifies(93, 0), "certifies").isTrue();
		requireThat(boundaries.certifies(92, 0), "oneFewer").isFalse();
		requireThat(boundaries.isClearlyBelow(54, 10), "clearlyBelow").isTrue();
		requireThat(boundaries.isClearlyBelow(55, 10), "oneMore").isFalse();
	}

	/**
	 * Verifies that every lookup in the first attempt's table agrees with the betting processes, for every state up
	 * to the cap.
	 */
	@Test
	public void lookupsAgreeWithBettingProcesses()
	{
		// Requirement: R-S2, R-S3
		DecisionBoundaries boundaries = FirstAttemptBoundaries.get();
		BettingProcess certification = new BettingProcess(ABOVE, REQUIRED_PASS_RATE);
		BettingProcess below = new BettingProcess(BELOW, REQUIRED_PASS_RATE);
		for (int failures = 0; failures <= boundaries.cap(); ++failures)
		{
			for (int passes = 0; passes + failures <= boundaries.cap(); ++passes)
			{
				String name = "S" + passes + "F" + failures;
				requireThat(boundaries.certifies(passes, failures), name + "Certifies").
					isEqualTo(certification.exceeds(passes, failures, boundaries.level()));
				requireThat(boundaries.isClearlyBelow(passes, failures), name + "ClearlyBelow").
					isEqualTo(below.exceeds(passes, failures, boundaries.level()));
			}
		}
	}

	/**
	 * Verifies that an unreachable certification is stored as one more pass than the cap allows.
	 */
	@Test
	public void unreachableCertificationExceedsCap()
	{
		// Requirement: R-S2
		DecisionBoundaries boundaries = FirstAttemptBoundaries.get();
		requireThat(boundaries.minimumCertifyingPasses().get(100), "minimumCertifyingPassesF100").isEqualTo(163);
		requireThat(boundaries.certifies(162, 100), "atCap").isFalse();
	}

	/**
	 * Verifies the all-pass minimum without a cap for the first three certification attempts.
	 */
	@Test
	public void allPassMinimumWithoutCap()
	{
		// Requirement: R-S2
		requireThat(DecisionBoundaries.allPassMinimum(ErrorBudget.certificationLevel(1)), "attempt1").isEqualTo(93);
		requireThat(DecisionBoundaries.allPassMinimum(ErrorBudget.certificationLevel(2)), "attempt2").isEqualTo(117);
		requireThat(DecisionBoundaries.allPassMinimum(ErrorBudget.certificationLevel(3)), "attempt3").isEqualTo(132);
	}

	/**
	 * Verifies each cell's proven-better and proven-worse boundaries, and that the lookups agree with the
	 * comparison for every state up to the cap.
	 */
	@Test
	public void comparisonBoundariesAgreeWithComparisons()
	{
		// Requirement: R-S7
		SignificanceLevel improvementLevel = ErrorBudget.improvementLevel(1, 3);
		Comparison ninety = new Comparison(new BigDecimal("0.9000"), new BigDecimal("0.5218"), improvementLevel);
		Comparison trivial = new Comparison(BigDecimal.ONE, BigDecimal.ZERO, improvementLevel);
		int cap = 140;
		DecisionBoundaries boundaries = DecisionBoundaries.compute(ErrorBudget.certificationLevel(1), cap,
			List.of(ninety, trivial));

		ComparisonBoundaries first = boundaries.comparisons().getFirst();
		requireThat(first.comparison(), "comparison").isEqualTo(ninety);
		requireThat(first.minimumProvenBetterPasses().subList(0, 3), "minimumProvenBetterPasses").
			isEqualTo(List.of(79, 110, 128));
		requireThat(first.maximumProvenWorsePasses().get(10), "maximumProvenWorseF10").isEqualTo(-1);
		requireThat(first.maximumProvenWorsePasses().get(20), "maximumProvenWorseF20").isEqualTo(0);
		requireThat(first.maximumProvenWorsePasses().get(30), "maximumProvenWorseF30").isEqualTo(6);
		for (ComparisonBoundaries cell : boundaries.comparisons())
		{
			Comparison comparison = cell.comparison();
			for (int failures = 0; failures <= cap; ++failures)
			{
				for (int passes = 0; passes + failures <= cap; ++passes)
				{
					String name = comparison.incumbentUpper() + "S" + passes + "F" + failures;
					requireThat(cell.isProvenBetter(passes, failures), name + "Better").
						isEqualTo(comparison.isProvenBetter(passes, failures));
					requireThat(cell.isProvenWorse(passes, failures), name + "Worse").
						isEqualTo(comparison.isProvenWorse(passes, failures));
				}
			}
		}
	}

	/**
	 * Verifies that a handwritten table answers lookups directly.
	 */
	@Test
	public void handwrittenTableAnswersLookups()
	{
		DecisionBoundaries boundaries = new DecisionBoundaries(ErrorBudget.certificationLevel(1), REQUIRED_PASS_RATE, 2,
			List.of(2, 2, 1), List.of(-1, 0, -1), List.of());
		requireThat(boundaries.certifies(2, 0), "certifies").isTrue();
		requireThat(boundaries.certifies(1, 0), "notCertified").isFalse();
		requireThat(boundaries.isClearlyBelow(0, 1), "clearlyBelow").isTrue();
		requireThat(boundaries.isClearlyBelow(1, 1), "notClearlyBelow").isFalse();
	}

	/**
	 * Verifies that a lookup beyond the cap is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsLookupBeyondCap()
	{
		FirstAttemptBoundaries.get().certifies(200, 63);
	}

	/**
	 * Verifies that a table whose lists do not cover every failure count up to the cap is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsShortTable()
	{
		new DecisionBoundaries(ErrorBudget.certificationLevel(1), REQUIRED_PASS_RATE, 2, List.of(2, 2),
			List.of(-1, 0, -1), List.of());
	}

	/**
	 * Verifies that a table in which a state both certifies and is clearly below is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsOverlappingDecisions()
	{
		new DecisionBoundaries(ErrorBudget.certificationLevel(1), REQUIRED_PASS_RATE, 2, List.of(2, 1, 1),
			List.of(-1, 1, -1), List.of());
	}
}
