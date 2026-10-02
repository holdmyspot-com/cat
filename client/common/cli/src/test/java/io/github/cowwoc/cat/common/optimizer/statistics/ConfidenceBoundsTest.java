package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.ABOVE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.BELOW;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Tests {@link ConfidenceBounds}.
 * <p>
 * Its reference values come from an
 * independent script that bisects the same e-values:
 * <table>
 *   <caption>Reference bounds checked by this class</caption>
 *   <tr><th>{@code (S, F)}</th><th>Threshold</th><th>Lower bound</th><th>Upper bound</th></tr>
 *   <tr><td>(93, 0)</td><td>50</td><td>0.950241738742</td><td>1</td></tr>
 *   <tr><td>(92, 0)</td><td>50</td><td>0.949716136456</td><td>1</td></tr>
 *   <tr><td>(3, 3)</td><td>50</td><td>0.051723989446</td><td>0.945315359467</td></tr>
 *   <tr><td>(4, 3)</td><td>50</td><td>0.099587882649</td><td>0.954702873335</td></tr>
 *   <tr><td>(40, 10)</td><td>50</td><td>0.583596353222</td><td>0.933393434624</td></tr>
 *   <tr><td>(45, 5)</td><td>400</td><td>0.682174839366</td><td>0.989699707232</td></tr>
 * </table>
 */
public final class ConfidenceBoundsTest
{
	/**
	 * Creates a test instance.
	 */
	public ConfidenceBoundsTest()
	{
	}

	/**
	 * Verifies the bounds against independently computed values and checks that each is labelled with its level.
	 */
	@Test
	public void boundsMatchReference()
	{
		// Requirement: R-S5
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireBounds(93, 0, level, 0.950_241_738_742, 1);
		requireBounds(92, 0, level, 0.949_716_136_456, 1);
		requireBounds(3, 3, level, 0.051_723_989_446, 0.945_315_359_467);
		requireBounds(4, 3, level, 0.099_587_882_649, 0.954_702_873_335);
		requireBounds(40, 10, level, 0.583_596_353_222, 0.933_393_434_624);
		requireBounds(45, 5, new SignificanceLevel(BigInteger.valueOf(400)), 0.682_174_839_366, 0.989_699_707_232);
	}

	/**
	 * Requires the lower and upper bounds for one cell's passes and failures.
	 *
	 * @param passes   the number of passes
	 * @param failures the number of failures
	 * @param level    the level
	 * @param lower    the expected lower bound
	 * @param upper    the expected upper bound
	 */
	private static void requireBounds(int passes, int failures, SignificanceLevel level, double lower, double upper)
	{
		String name = "S" + passes + "F" + failures;
		ConfidenceBound lowerBound = ConfidenceBounds.lower(passes, failures, level);
		requireThat(lowerBound.value(), name + "Lower").isBetween(lower - 1.0e-9, lower + 1.0e-9);
		requireThat(lowerBound.level(), name + "LowerLevel").isEqualTo(level);
		ConfidenceBound upperBound = ConfidenceBounds.upper(passes, failures, level);
		requireThat(upperBound.value(), name + "Upper").isBetween(upper - 1.0e-9, true,
			Math.min(1, upper + 1.0e-9), true);
		requireThat(upperBound.level(), name + "UpperLevel").isEqualTo(level);
	}

	/**
	 * Verifies that the lower bound at {@code alpha_1} exceeds 0.95 exactly when the cell certifies, and that the
	 * upper bound is below 0.95 exactly when it is clearly below.
	 */
	@Test
	public void boundsAgreeWithDecisions()
	{
		// Requirement: R-S5
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireThat(ConfidenceBounds.lower(93, 0, level).value(), "certified").isGreaterThan(0.95);
		requireThat(ConfidenceBounds.lower(92, 0, level).value(), "notCertified").isLessThan(0.95);
		requireThat(ConfidenceBounds.lower(147, 1, level).value(), "certifiedAfterFailure").isGreaterThan(0.95);
		requireThat(ConfidenceBounds.lower(146, 1, level).value(), "notCertifiedAfterFailure").isLessThan(0.95);
		requireThat(ConfidenceBounds.upper(3, 3, level).value(), "clearlyBelow").isLessThan(0.95);
		requireThat(ConfidenceBounds.upper(4, 3, level).value(), "notClearlyBelow").isGreaterThan(0.95);
	}

	/**
	 * Verifies that each bound inverts its test at an arbitrary null rate: the test rejects null rates just outside
	 * the bound and accepts null rates just inside it.
	 */
	@Test
	public void boundsInvertTestsAtAnyNullRate()
	{
		// Requirement: R-S5
		SignificanceLevel level = new SignificanceLevel(BigInteger.valueOf(400));
		double lower = ConfidenceBounds.lower(45, 5, level).value();
		requireThat(new BettingProcess(ABOVE, BigDecimal.valueOf(lower - 1.0e-6)).exceeds(45, 5, level),
			"belowLower").isTrue();
		requireThat(new BettingProcess(ABOVE, BigDecimal.valueOf(lower + 1.0e-6)).exceeds(45, 5, level),
			"aboveLower").isFalse();
		double upper = ConfidenceBounds.upper(45, 5, level).value();
		requireThat(new BettingProcess(BELOW, BigDecimal.valueOf(upper + 1.0e-6)).exceeds(45, 5, level),
			"aboveUpper").isTrue();
		requireThat(new BettingProcess(BELOW, BigDecimal.valueOf(upper - 1.0e-6)).exceeds(45, 5, level),
			"belowUpper").isFalse();
	}

	/**
	 * Verifies that no passes give a lower bound of 0 and no failures give an upper bound of 1.
	 */
	@Test
	public void boundsWithoutEvidenceAreTrivial()
	{
		// Requirement: R-S5
		SignificanceLevel level = ErrorBudget.certificationLevel(1);
		requireThat(ConfidenceBounds.lower(0, 7, level).value(), "lowerWithoutPasses").isEqualTo(0.0);
		requireThat(ConfidenceBounds.upper(7, 0, level).value(), "upperWithoutFailures").isEqualTo(1.0);
		requireThat(ConfidenceBounds.lower(0, 0, level).value(), "lowerWithoutOutcomes").isEqualTo(0.0);
		requireThat(ConfidenceBounds.upper(0, 0, level).value(), "upperWithoutOutcomes").isEqualTo(1.0);
	}

	/**
	 * Verifies that a negative number of failures is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsNegativeFailures()
	{
		ConfidenceBounds.upper(1, -1, ErrorBudget.certificationLevel(1));
	}

	/**
	 * Verifies that a bound outside {@code [0, 1]} is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBoundAboveOne()
	{
		new ConfidenceBound(1.5, ErrorBudget.certificationLevel(1));
	}
}
