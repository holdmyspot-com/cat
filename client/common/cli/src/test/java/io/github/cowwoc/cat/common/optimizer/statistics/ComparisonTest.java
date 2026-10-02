package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.REQUIRED_PASS_RATE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.BELOW;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Tests {@link Comparison}.
 * <p>
 * The tests check these reference values. The other boundary pairs come from an independent script that
 * compares exact rationals.
 * <table>
 *   <caption>Reference values checked by this class</caption>
 *   <tr><th>Quantity</th><th>Reference value</th><th>Checked value</th></tr>
 *   <tr><td>all-pass trials proving improvement over {@code UI = 0.90} with 3 cells
 *     ({@code beta_1 / 3}, threshold 1200)</td><td>79</td><td>79</td></tr>
 *   <tr><td>the same with the full {@code alpha_1 = 2%} (threshold 50)</td><td>45</td>
 *     <td>45</td></tr>
 * </table>
 */
public final class ComparisonTest
{
	/**
	 * Creates a test instance.
	 */
	public ComparisonTest()
	{
	}

	/**
	 * Verifies that freezing rounds the incumbent's upper bound up and its lower bound down to 4 decimal places.
	 * <p>
	 * For 40 passes and 10 failures at threshold 1200, the display bounds are about 0.95659 and 0.52184, so
	 * {@code UI = 0.9566} and {@code LI = 0.5218}. For 84 passes and 16 failures at threshold 400, {@code UI = 0.9458}
	 * and {@code LI = 0.6682}.
	 */
	@Test
	public void freezeRoundsOutward()
	{
		// Requirement: R-S7
		SignificanceLevel incumbentLevel = ErrorBudget.incumbentLevel(1, 3);
		SignificanceLevel improvementLevel = ErrorBudget.improvementLevel(1, 3);
		Comparison comparison = Comparison.freeze(40, 10, incumbentLevel, improvementLevel);
		requireThat(comparison.incumbentUpper(), "upper").isEqualTo(new BigDecimal("0.9566"));
		requireThat(comparison.incumbentLower(), "lower").isEqualTo(new BigDecimal("0.5218"));
		requireThat(comparison.level(), "level").isEqualTo(improvementLevel);
		requireRoundedOutward(40, 10, incumbentLevel, comparison);

		SignificanceLevel singleCell = ErrorBudget.incumbentLevel(1, 1);
		Comparison deficient = Comparison.freeze(84, 16, singleCell, ErrorBudget.improvementLevel(1, 1));
		requireThat(deficient.incumbentUpper(), "deficientUpper").isEqualTo(new BigDecimal("0.9458"));
		requireThat(deficient.incumbentLower(), "deficientLower").isEqualTo(new BigDecimal("0.6682"));
		requireRoundedOutward(84, 16, singleCell, deficient);
	}

	/**
	 * Requires that the frozen bounds lie within one grid step outside the display bounds.
	 *
	 * @param passes     the incumbent's passes
	 * @param failures   the incumbent's failures
	 * @param level      the incumbent's level
	 * @param comparison the frozen comparison
	 */
	private static void requireRoundedOutward(int passes, int failures, SignificanceLevel level, Comparison comparison)
	{
		double upper = ConfidenceBounds.upper(passes, failures, level).value();
		double frozenUpper = comparison.incumbentUpper().doubleValue();
		requireThat(frozenUpper, "frozenUpper").isBetween(upper, true, upper + 0.0001, false);
		double lower = ConfidenceBounds.lower(passes, failures, level).value();
		double frozenLower = comparison.incumbentLower().doubleValue();
		requireThat(frozenLower, "frozenLower").isBetween(lower - 0.0001, false, lower, true);
	}

	/**
	 * Verifies that an incumbent without failures has {@code UI = 1}, and one without passes has {@code LI = 0}.
	 */
	@Test
	public void freezeWithoutEvidenceIsTrivial()
	{
		// Requirement: R-S7
		SignificanceLevel level = ErrorBudget.incumbentLevel(1, 1);
		Comparison allPass = Comparison.freeze(100, 0, level, level);
		requireThat(allPass.incumbentUpper(), "upper").isEqualTo(new BigDecimal("1.0000"));
		Comparison allFail = Comparison.freeze(0, 100, level, level);
		requireThat(allFail.incumbentLower(), "lower").isEqualTo(new BigDecimal("0.0000"));
	}

	/**
	 * Verifies both sides of the proven-better threshold against {@code UI = 0.9566} at threshold 1200, and the
	 * all-pass counts against {@code UI = 0.90} under joint and per-cell improvement budgets.
	 */
	@Test
	public void provenBetterBoundaryPairs()
	{
		// Requirement: R-S7
		SignificanceLevel improvementLevel = ErrorBudget.improvementLevel(1, 3);
		Comparison frozen = new Comparison(new BigDecimal("0.9566"), new BigDecimal("0.5218"), improvementLevel);
		int[] minimumPasses = {187, 262, 306};
		for (int failures = 0; failures < minimumPasses.length; ++failures)
		{
			int passes = minimumPasses[failures];
			requireThat(frozen.isProvenBetter(passes, failures), "F" + failures + "Better").isTrue();
			requireThat(frozen.isProvenBetter(passes - 1, failures), "F" + failures + "OneFewer").isFalse();
		}

		Comparison ninety = new Comparison(new BigDecimal("0.9000"), new BigDecimal("0.5000"), improvementLevel);
		requireThat(ninety.isProvenBetter(79, 0), "threeCells").isTrue();
		requireThat(ninety.isProvenBetter(78, 0), "threeCellsOneFewer").isFalse();
		Comparison fullAlpha = new Comparison(new BigDecimal("0.9000"), new BigDecimal("0.5000"),
			ErrorBudget.certificationLevel(1));
		requireThat(fullAlpha.isProvenBetter(45, 0), "fullAlpha").isTrue();
		requireThat(fullAlpha.isProvenBetter(44, 0), "fullAlphaOneFewer").isFalse();
	}

	/**
	 * Verifies both sides of the proven-worse threshold against {@code LI = 0.5218} and {@code LI = 0.90} at
	 * threshold 1200.
	 */
	@Test
	public void provenWorseBoundaryPairs()
	{
		// Requirement: R-S7
		SignificanceLevel improvementLevel = ErrorBudget.improvementLevel(1, 3);
		Comparison frozen = new Comparison(new BigDecimal("0.9566"), new BigDecimal("0.5218"), improvementLevel);
		requireThat(frozen.isProvenWorse(0, 10), "F10").isFalse();
		requireThat(frozen.isProvenWorse(0, 20), "F20").isTrue();
		requireThat(frozen.isProvenWorse(1, 20), "F20OneMore").isFalse();
		requireThat(frozen.isProvenWorse(6, 30), "F30").isTrue();
		requireThat(frozen.isProvenWorse(7, 30), "F30OneMore").isFalse();

		Comparison ninety = new Comparison(new BigDecimal("0.9900"), new BigDecimal("0.9000"), improvementLevel);
		requireThat(ninety.isProvenWorse(13, 10), "ninetyF10").isTrue();
		requireThat(ninety.isProvenWorse(14, 10), "ninetyF10OneMore").isFalse();
		requireThat(ninety.isProvenWorse(55, 20), "ninetyF20").isTrue();
		requireThat(ninety.isProvenWorse(56, 20), "ninetyF20OneMore").isFalse();
	}

	/**
	 * Verifies that nothing is proven better than an incumbent whose upper bound is 1, and nothing is proven worse
	 * than one whose lower bound is 0.
	 */
	@Test
	public void trivialBoundsNeverDecide()
	{
		// Requirement: R-S7
		Comparison trivial = new Comparison(BigDecimal.ONE, BigDecimal.ZERO, ErrorBudget.improvementLevel(1, 1));
		requireThat(trivial.isProvenBetter(10_000, 0), "better").isFalse();
		requireThat(trivial.isProvenWorse(0, 10_000), "worse").isFalse();
	}

	/**
	 * Verifies that the displayed difference bound {@code LC - UI} is positive when the candidate is certified and
	 * the incumbent is proven deficient.
	 * <p>
	 * The incumbent has 84 passes and 16 failures, which proves deficiency at {@code gamma_1 / 1}. Each candidate
	 * state is the fewest certifying passes of attempt 1 for 0 to 2 failures.
	 */
	@Test
	public void certifiedOverDeficientIsPositive()
	{
		// Requirement: R-S7
		SignificanceLevel incumbentLevel = ErrorBudget.incumbentLevel(1, 1);
		requireThat(new BettingProcess(BELOW, REQUIRED_PASS_RATE).exceeds(84, 16, incumbentLevel), "deficient").
			isTrue();
		Comparison comparison = Comparison.freeze(84, 16, incumbentLevel, ErrorBudget.improvementLevel(1, 1));

		SignificanceLevel certificationLevel = ErrorBudget.certificationLevel(1);
		int[][] certifiedStates = {{93, 0}, {147, 1}, {184, 2}};
		for (int[] state : certifiedStates)
		{
			ConfidenceBound lower = ConfidenceBounds.lower(state[0], state[1], certificationLevel);
			ConfidenceBound upper = ConfidenceBounds.upper(state[0], state[1], certificationLevel);
			Interval difference = comparison.difference(lower, upper);
			String name = "S" + state[0] + "F" + state[1];
			requireThat(difference.lower(), name + "Lower").isPositive();
			requireThat(difference.lower(), name + "LowerValue").
				isEqualTo(lower.value() - comparison.incumbentUpper().doubleValue());
			requireThat(difference.upper(), name + "Upper").
				isEqualTo(upper.value() - comparison.incumbentLower().doubleValue());
		}
	}

	/**
	 * Verifies that an incumbent bound off the 4-decimal grid is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsUpperOffGrid()
	{
		new Comparison(new BigDecimal("0.95001"), new BigDecimal("0.5"), ErrorBudget.improvementLevel(1, 1));
	}

	/**
	 * Verifies that a lower bound that is not below the upper bound is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsCrossedBounds()
	{
		new Comparison(new BigDecimal("0.9"), new BigDecimal("0.9"), ErrorBudget.improvementLevel(1, 1));
	}

	/**
	 * Verifies that the bounds are normalized to 4 decimal places, so that equal bounds compare equal after
	 * persistence.
	 */
	@Test
	public void normalizesBoundsToFourDecimals()
	{
		SignificanceLevel level = new SignificanceLevel(BigInteger.valueOf(7));
		Comparison comparison = new Comparison(new BigDecimal("0.99"), new BigDecimal("0.1"), level);
		requireThat(comparison.incumbentUpper(), "upper").isEqualTo(new BigDecimal("0.9900"));
		requireThat(comparison.incumbentLower(), "lower").isEqualTo(new BigDecimal("0.1000"));
	}
}
