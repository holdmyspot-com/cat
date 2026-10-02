package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.ABOVE;
import static io.github.cowwoc.cat.common.optimizer.statistics.BettingProcess.Side.BELOW;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * The frozen comparison between a candidate and the incumbent in one cell {@code k}, one category under one trial
 * profile, of a certification attempt.
 * <p>
 * The candidate is <i>proven better</i> when {@code E_up}, with the null rate {@code UI(k)} in place of 0.95, exceeds
 * the attempt's per-cell improvement threshold {@code K / beta_i}. It is <i>proven worse</i> when
 * {@code E_down}, with the null rate {@code LI(k)}, exceeds the same threshold. {@code UI(k)} and {@code LI(k)} are
 * the incumbent's bounds at its baseline's level {@code gamma_b / K}, rounded outward to 4 decimal places and frozen
 * when the attempt is committed. {@code K} is the number of cells in the contract, and {@link ErrorBudget} defines the
 * improvement budget {@code beta_i} and the incumbent budget {@code gamma_b}.
 *
 * @param incumbentUpper the incumbent's upper bound {@code UI(k)}, a multiple of 0.0001 in {@code (0, 1]}
 * @param incumbentLower the incumbent's lower bound {@code LI(k)}, a multiple of 0.0001 in {@code [0, UI(k))}
 * @param level          the per-cell improvement level {@code beta_i / K}
 */
public record Comparison(BigDecimal incumbentUpper, BigDecimal incumbentLower, SignificanceLevel level)
{
	/**
	 * The number of decimal places of a frozen bound.
	 */
	private static final int SCALE = 4;
	/**
	 * The distance between adjacent frozen bounds.
	 */
	private static final BigDecimal GRID_STEP = BigDecimal.valueOf(1, SCALE);
	/**
	 * The number of grid steps between 0 and 1.
	 */
	private static final int GRID_POINTS = 10_000;

	/**
	 * Creates a comparison.
	 *
	 * @param incumbentUpper the incumbent's upper bound {@code UI(k)}, a multiple of 0.0001 in {@code (0, 1]}
	 * @param incumbentLower the incumbent's lower bound {@code LI(k)}, a multiple of 0.0001 in {@code [0, UI(k))}
	 * @param level          the per-cell improvement level {@code beta_i / K}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code incumbentUpper} or {@code incumbentLower} are not multiples of 0.0001
	 *                                  in their ranges
	 */
	public Comparison
	{
		requireThat(incumbentUpper, "incumbentUpper").isGreaterThan(BigDecimal.ZERO).
			isLessThanOrEqualTo(BigDecimal.ONE).isMultipleOf(GRID_STEP);
		requireThat(incumbentLower, "incumbentLower").isGreaterThanOrEqualTo(BigDecimal.ZERO).
			isLessThan(incumbentUpper, "incumbentUpper").isMultipleOf(GRID_STEP);
		requireThat(level, "level").isNotNull();
		incumbentUpper = incumbentUpper.setScale(SCALE, RoundingMode.UNNECESSARY);
		incumbentLower = incumbentLower.setScale(SCALE, RoundingMode.UNNECESSARY);
	}

	/**
	 * Freezes the comparison from the incumbent's completed baseline.
	 * <p>
	 * {@code UI(k)} is the smallest multiple of 0.0001 at which {@code E_down} reaches the incumbent threshold, or 1
	 * if none below 1 does. Because {@code E_down} increases with the null rate, this rounds the exact upper bound up.
	 * {@code LI(k)} is the largest multiple of 0.0001 at which {@code E_up} reaches the threshold, or 0 if none above
	 * 0 does, which rounds the exact lower bound down. Both are found by bisection over the grid with exact
	 * comparisons.
	 *
	 * @param incumbentPasses   the incumbent's representative passes in the cell
	 * @param incumbentFailures the incumbent's representative failures in the cell
	 * @param incumbentLevel    the baseline's per-cell level {@code gamma_b / K}
	 * @param improvementLevel  the attempt's per-cell improvement level {@code beta_i / K}
	 * @return the frozen comparison
	 * @throws NullPointerException     if any of the levels are null
	 * @throws IllegalArgumentException if {@code incumbentPasses} or {@code incumbentFailures} are negative
	 */
	public static Comparison freeze(int incumbentPasses, int incumbentFailures, SignificanceLevel incumbentLevel,
		SignificanceLevel improvementLevel)
	{
		requireThat(incumbentPasses, "incumbentPasses").isNotNegative();
		requireThat(incumbentFailures, "incumbentFailures").isNotNegative();
		requireThat(incumbentLevel, "incumbentLevel").isNotNull();
		requireThat(improvementLevel, "improvementLevel").isNotNull();

		BigInteger threshold = incumbentLevel.threshold();
		int low = 1;
		int high = GRID_POINTS;
		while (low < high)
		{
			int middle = (low + high) / 2;
			if (new BettingProcess(BELOW, gridPoint(middle)).compare(incumbentPasses, incumbentFailures, threshold) >= 0)
				high = middle;
			else
				low = middle + 1;
		}
		BigDecimal upper = gridPoint(low);

		low = 0;
		high = GRID_POINTS - 1;
		while (low < high)
		{
			int middle = (low + high + 1) / 2;
			if (new BettingProcess(ABOVE, gridPoint(middle)).compare(incumbentPasses, incumbentFailures, threshold) >= 0)
				low = middle;
			else
				high = middle - 1;
		}
		return new Comparison(upper, gridPoint(low), improvementLevel);
	}

	/**
	 * Returns a grid point.
	 *
	 * @param index the number of grid steps above 0
	 * @return {@code index / 10^4}
	 */
	private static BigDecimal gridPoint(int index)
	{
		return BigDecimal.valueOf(index, SCALE);
	}

	/**
	 * Indicates whether the candidate's outcomes in this attempt prove it better than the incumbent.
	 *
	 * @param passes   the candidate's adopted passes in the attempt
	 * @param failures the candidate's adopted failures in the attempt
	 * @return {@code true} if {@code E_up} at null rate {@code UI(k)} exceeds the improvement threshold; always
	 *   {@code false} if {@code UI(k) = 1}, because no pass probability exceeds 1
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative
	 */
	public boolean isProvenBetter(int passes, int failures)
	{
		requireThat(passes, "passes").isNotNegative();
		requireThat(failures, "failures").isNotNegative();
		return incumbentUpper.compareTo(BigDecimal.ONE) < 0 &&
			new BettingProcess(ABOVE, incumbentUpper).exceeds(passes, failures, level);
	}

	/**
	 * Indicates whether the candidate's outcomes in this attempt prove it worse than the incumbent.
	 *
	 * @param passes   the candidate's adopted passes in the attempt
	 * @param failures the candidate's adopted failures in the attempt
	 * @return {@code true} if {@code E_down} at null rate {@code LI(k)} exceeds the improvement threshold; always
	 *   {@code false} if {@code LI(k) = 0}, because no pass probability is below 0
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative
	 */
	public boolean isProvenWorse(int passes, int failures)
	{
		requireThat(passes, "passes").isNotNegative();
		requireThat(failures, "failures").isNotNegative();
		return incumbentLower.signum() > 0 && new BettingProcess(BELOW, incumbentLower).exceeds(passes, failures, level);
	}

	/**
	 * Returns the displayed difference interval {@code [LC - UI(k), UC - LI(k)]} between the candidate's and the
	 * incumbent's pass probabilities.
	 * <p>
	 * If the candidate is certified at {@code alpha_i}, its lower bound {@code LC} at {@code alpha_i} is above 0.95;
	 * if the incumbent is proven deficient, the rounded {@code UI(k)} is at most 0.95. The lower endpoint is then
	 * positive, so the improvement follows from certification and deficiency without additional trials.
	 *
	 * @param candidateLower the candidate's lower bound {@code LC}
	 * @param candidateUpper the candidate's upper bound {@code UC}
	 * @return the difference interval
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code candidateLower} is greater than {@code candidateUpper}
	 */
	public Interval difference(ConfidenceBound candidateLower, ConfidenceBound candidateUpper)
	{
		requireThat(candidateLower, "candidateLower").isNotNull();
		requireThat(candidateUpper, "candidateUpper").isNotNull();
		requireThat(candidateLower.value(), "candidateLower.value()").
			isLessThanOrEqualTo(candidateUpper.value(), "candidateUpper.value()");
		return new Interval(candidateLower.value() - incumbentUpper.doubleValue(),
			candidateUpper.value() - incumbentLower.doubleValue());
	}
}
