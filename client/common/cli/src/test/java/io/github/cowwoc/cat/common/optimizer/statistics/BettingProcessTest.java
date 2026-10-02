package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.REQUIRED_PASS_RATE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.ABOVE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.BELOW;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Tests {@link BettingProcess}.
 * <p>
 * The tests check these reference values. The boundary pairs come from an independent script that compares
 * exact rationals.
 * <table>
 *   <caption>Reference values checked by this class</caption>
 *   <tr><th>Quantity</th><th>Reference value</th><th>Checked value</th></tr>
 *   <tr><td>all-pass minimum for attempts 1, 2, 3, 4, 5</td><td>93, 117, 132, 143, 152</td>
 *     <td>93, 117, 132, 143, 152</td></tr>
 *   <tr><td>all-pass minimum for attempts 10, 50, 100</td><td>179, 244, 271</td>
 *     <td>179, 244, 271</td></tr>
 *   <tr><td>fewest failures of 50 proving deficiency, {@code b = 1}, {@code K} = 1, 2, 3, 5, 8</td>
 *     <td>10, 11, 11, 12, 12</td><td>10, 11, 11, 12, 12</td></tr>
 *   <tr><td>fewest failures of 100 proving deficiency, {@code b = 1}, {@code K} = 1, 2, 3, 5, 8</td>
 *     <td>16, 16, 17, 17, 17</td><td>16, 16, 17, 17, 17</td></tr>
 *   <tr><td>all-pass minimum with an unsplit 5% ({@code alpha_1 = 0.025})</td><td>88</td>
 *     <td>88</td></tr>
 *   <tr><td>all-pass minimum with a certification budget of 0.025 ({@code alpha_1 = 0.0125})</td>
 *     <td>103</td><td>103</td></tr>
 *   <tr><td>extra failures to prove deficiency when the incumbent share halves from 0.01 to
 *     0.005</td><td>at most 1</td><td>0 or 1 (50 trials: 10, 10, 11, 11, 11; 100 trials: 15, 16, 16, 16, 17
 *     at 0.01)</td></tr>
 *   <tr><td>attempt 1's all-pass minimum under {@code 0.4 / ((i + 9)(i + 10))}</td><td>130</td>
 *     <td>130</td></tr>
 *   <tr><td>all-pass minimum under an equal split over 100 attempts</td><td>177</td>
 *     <td>177</td></tr>
 * </table>
 */
public final class BettingProcessTest
{
	/**
	 * Creates a test instance.
	 */
	public BettingProcessTest()
	{
	}

	/**
	 * Verifies, for attempts 1 to 5 and 0 to 2 failures, that the fewest certifying passes certify and one fewer
	 * pass does not.
	 */
	@Test
	public void certificationBoundaryPairs()
	{
		// Requirement: R-S2
		int[][] minimumPasses =
		{
			{93, 147, 184},
			{117, 174, 212},
			{132, 192, 230},
			{143, 204, 243},
			{152, 215, 253}
		};
		BettingProcess certification = new BettingProcess(ABOVE, REQUIRED_PASS_RATE);
		for (int index = 1; index <= minimumPasses.length; ++index)
		{
			SignificanceLevel level = ErrorBudget.certificationLevel(index);
			for (int failures = 0; failures < minimumPasses[index - 1].length; ++failures)
			{
				int passes = minimumPasses[index - 1][failures];
				String pair = "i" + index + "F" + failures;
				requireThat(certification.exceeds(passes, failures, level), pair + "Certifies").isTrue();
				requireThat(certification.exceeds(passes - 1, failures, level), pair + "OneFewer").isFalse();
			}
		}
	}

	/**
	 * Verifies the all-pass minimums per attempt and under alternative budget splits.
	 */
	@Test
	public void allPassMinimumsMatchReference()
	{
		// Requirement: R-S2
		int[] indexes = {1, 2, 3, 4, 5, 10, 50, 100};
		int[] minimums = {93, 117, 132, 143, 152, 179, 244, 271};
		for (int row = 0; row < indexes.length; ++row)
		{
			SignificanceLevel level = ErrorBudget.certificationLevel(indexes[row]);
			requireAllPassMinimum(level, minimums[row], "i" + indexes[row]);
		}
		// An unsplit 5% gives alpha_1 = 0.025; a certification budget of 0.025 gives alpha_1 = 0.0125
		requireAllPassMinimum(level(40), 88, "unsplit");
		requireAllPassMinimum(level(80), 103, "smallerShare");
		// 0.4 / ((1 + 9)(1 + 10)) = 1 / 275; an equal split gives 0.04 / 100 = 1 / 2500
		requireAllPassMinimum(level(275), 130, "flatterSeries");
		requireAllPassMinimum(level(2500), 177, "equalSplit");
	}

	/**
	 * Requires that {@code minimum} all-pass outcomes certify at {@code level} and that one fewer do not.
	 *
	 * @param level   the certification level
	 * @param minimum the expected all-pass minimum
	 * @param name    the name of the checked value
	 */
	private static void requireAllPassMinimum(SignificanceLevel level, int minimum, String name)
	{
		BettingProcess certification = new BettingProcess(ABOVE, REQUIRED_PASS_RATE);
		requireThat(certification.exceeds(minimum, 0, level), name + "Certifies").isTrue();
		requireThat(certification.exceeds(minimum - 1, 0, level), name + "OneFewer").isFalse();
	}

	/**
	 * Verifies that an e-value exactly equal to the threshold does not decide, on both sides.
	 * <p>
	 * At {@code t = 19 / 262144}, {@code E_up(1, 0) = 0.24 + 0.76 / t = 10486} exactly. At {@code t = 627 / 640},
	 * {@code E_down(0, 1) = (3 + 8151 / 247) / 3 = 12} exactly.
	 */
	@Test
	public void equalityNeverDecides()
	{
		// Requirement: R-S2, R-S3
		BettingProcess above = new BettingProcess(ABOVE, new BigDecimal("0.000072479248046875"));
		requireThat(above.exceeds(1, 0, level(10_486)), "aboveAtEquality").isFalse();
		requireThat(above.exceeds(1, 0, level(10_485)), "aboveBelowEquality").isTrue();
		requireThat(above.compareExactly(1, 0, BigInteger.valueOf(10_486)), "aboveExactComparison").isEqualTo(0);

		BettingProcess below = new BettingProcess(BELOW, new BigDecimal("0.9796875"));
		requireThat(below.exceeds(0, 1, level(12)), "belowAtEquality").isFalse();
		requireThat(below.exceeds(0, 1, level(11)), "belowBelowEquality").isTrue();
		requireThat(below.compareExactly(0, 1, BigInteger.valueOf(12)), "belowExactComparison").isEqualTo(0);
	}

	/**
	 * Verifies that the floating-point shortcut decides every comparison exactly as rational arithmetic does, near
	 * the certification and clearly-below boundaries of attempt 1 and near a frozen incumbent bound.
	 */
	@Test
	public void shortcutAgreesWithExactArithmetic()
	{
		// Requirement: R-S2, R-S3
		requireAgreement(new BettingProcess(ABOVE, REQUIRED_PASS_RATE), 80, 260, 3, BigInteger.valueOf(50));
		requireAgreement(new BettingProcess(BELOW, REQUIRED_PASS_RATE), 0, 70, 12, BigInteger.valueOf(50));
		requireAgreement(new BettingProcess(ABOVE, new BigDecimal("0.9566")), 150, 320, 2, BigInteger.valueOf(1200));
		requireAgreement(new BettingProcess(BELOW, new BigDecimal("0.5218")), 0, 20, 30, BigInteger.valueOf(1200));
		// A threshold wider than 53 bits is shifted right to 53 bits before its logarithm is taken
		requireAgreement(new BettingProcess(ABOVE, REQUIRED_PASS_RATE), 1710, 1750, 1, BigInteger.TEN.pow(30));
	}

	/**
	 * Requires that {@code compare()} and {@code compareExactly()} agree over a range of counts.
	 *
	 * @param process         the process
	 * @param minimumPasses   the smallest number of passes to compare
	 * @param maximumPasses   the largest number of passes to compare
	 * @param maximumFailures the largest number of failures to compare
	 * @param threshold       the threshold
	 */
	private static void requireAgreement(BettingProcess process, int minimumPasses, int maximumPasses,
		int maximumFailures, BigInteger threshold)
	{
		for (int failures = 0; failures <= maximumFailures; ++failures)
		{
			for (int passes = minimumPasses; passes <= maximumPasses; ++passes)
			{
				int exact = process.compareExactly(passes, failures, threshold);
				requireThat(process.compare(passes, failures, threshold), "S" + passes + "F" + failures).
					isEqualTo(exact);
			}
		}
	}

	/**
	 * Verifies, for attempts 1 to 5, that the largest clearly-below pass counts at 3, 5 and 10 failures are clearly
	 * below and that one more pass is not.
	 */
	@Test
	public void clearlyBelowBoundaryPairs()
	{
		// Requirement: R-S3
		int[] failures = {3, 5, 10};
		int[][] maximumPasses =
		{
			{3, 12, 54},
			{1, 9, 46},
			{0, 7, 41},
			{-1, 6, 38},
			{-1, 6, 36}
		};
		BettingProcess below = new BettingProcess(BELOW, REQUIRED_PASS_RATE);
		for (int index = 1; index <= maximumPasses.length; ++index)
		{
			SignificanceLevel level = ErrorBudget.certificationLevel(index);
			for (int column = 0; column < failures.length; ++column)
			{
				int passes = maximumPasses[index - 1][column];
				String pair = "i" + index + "F" + failures[column];
				if (passes >= 0)
					requireThat(below.exceeds(passes, failures[column], level), pair + "ClearlyBelow").isTrue();
				requireThat(below.exceeds(passes + 1, failures[column], level), pair + "OneMore").isFalse();
			}
		}
	}

	/**
	 * Verifies the fewest failures that prove the first baseline's incumbent deficient at 50 and 100 trials.
	 */
	@Test
	public void deficiencyThresholdsMatchReference()
	{
		// Requirement: R-S4
		int[] cellCounts = {1, 2, 3, 5, 8};
		requireFewestFailures(50, new int[]{10, 11, 11, 12, 12}, cellCounts, 400);
		requireFewestFailures(100, new int[]{16, 16, 17, 17, 17}, cellCounts, 400);
	}

	/**
	 * Verifies that halving the incumbent share from 0.01 to 0.005 adds at most one failure to a deficiency proof.
	 */
	@Test
	public void halvingIncumbentShareCostsOneFailure()
	{
		// Requirement: R-S4
		int[] cellCounts = {1, 2, 3, 5, 8};
		// At 0.01, the first baseline's threshold is K / (0.01 / 2) = 200 K
		requireFewestFailures(50, new int[]{10, 10, 11, 11, 11}, cellCounts, 200);
		requireFewestFailures(100, new int[]{15, 16, 16, 16, 17}, cellCounts, 200);
	}

	/**
	 * Requires the fewest failures among {@code trials} that prove deficiency for each cell count.
	 *
	 * @param trials           the number of incumbent trials
	 * @param fewestFailures   the expected fewest failures for each cell count
	 * @param cellCounts       the cell counts {@code K}
	 * @param thresholdPerCell the threshold divided by {@code K}
	 */
	private static void requireFewestFailures(int trials, int[] fewestFailures, int[] cellCounts,
		int thresholdPerCell)
	{
		BettingProcess below = new BettingProcess(BELOW, REQUIRED_PASS_RATE);
		for (int column = 0; column < cellCounts.length; ++column)
		{
			SignificanceLevel level = level((long) thresholdPerCell * cellCounts[column]);
			if (thresholdPerCell == 400)
			{
				requireThat(level, "level").isEqualTo(ErrorBudget.incumbentLevel(1, cellCounts[column]));
			}
			int failures = fewestFailures[column];
			String name = "n" + trials + "K" + cellCounts[column];
			requireThat(below.exceeds(trials - failures, failures, level), name + "Deficient").isTrue();
			requireThat(below.exceeds(trials - failures + 1, failures - 1, level), name + "OneFewer").isFalse();
		}
	}

	/**
	 * Verifies the logarithms of both e-values against independently computed values.
	 */
	@Test
	public void logValueMatchesReference()
	{
		// Requirement: R-S2, R-S3
		double above = new BettingProcess(ABOVE, REQUIRED_PASS_RATE).logValue(100, 1);
		requireThat(above, "above").isBetween(2.134_915_277_597_6 - 1.0e-12, 2.134_915_277_597_6 + 1.0e-12);
		double below = new BettingProcess(BELOW, REQUIRED_PASS_RATE).logValue(40, 10);
		requireThat(below, "below").isBetween(5.993_471_347_877_4 - 1.0e-12, 5.993_471_347_877_4 + 1.0e-12);
	}

	/**
	 * Verifies that the bet on a 100% pass rate stops contributing after the first failure.
	 * <p>
	 * With no failures, {@code E_up(1, 0) = 0.24 + 0.76 / 0.95 = 1.04}. After one failure, the 100% bet's weight 0.2
	 * is lost: {@code E_up(0, 1) = 0.1 * 0.6 + 0.2 * 0.4 + 0.5 * 0.2 = 0.24}.
	 */
	@Test
	public void certainBetVanishesAfterFailure()
	{
		// Requirement: R-S2
		BettingProcess certification = new BettingProcess(ABOVE, REQUIRED_PASS_RATE);
		requireThat(certification.compareExactly(1, 0, BigInteger.ONE), "growsAfterPass").isEqualTo(1);
		requireThat(Math.exp(certification.logValue(0, 1)), "afterFailure").isBetween(0.24 - 1.0e-12, 0.24 + 1.0e-12);
	}

	/**
	 * Verifies that a null rate of 1 is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNullRateOne()
	{
		new BettingProcess(ABOVE, BigDecimal.ONE);
	}

	/**
	 * Verifies that a null rate of 0 is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNullRateZero()
	{
		new BettingProcess(BELOW, BigDecimal.ZERO);
	}

	/**
	 * Verifies that a negative number of passes is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNegativePasses()
	{
		new BettingProcess(ABOVE, REQUIRED_PASS_RATE).exceeds(-1, 0, level(50));
	}

	/**
	 * Returns a level with the given threshold.
	 *
	 * @param threshold the threshold
	 * @return the level
	 */
	private static SignificanceLevel level(long threshold)
	{
		return new SignificanceLevel(BigInteger.valueOf(threshold));
	}
}
