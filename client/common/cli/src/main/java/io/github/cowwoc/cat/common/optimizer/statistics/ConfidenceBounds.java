package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;

/**
 * Display confidence bounds that invert the {@link BettingProcess} tests over the null rate.
 * <p>
 * The lower bound is the null rate at which the certification e-value falls to the level's threshold: every smaller
 * null rate is rejected. The upper bound is the null rate at which the below-minimum e-value rises to the threshold:
 * every larger null rate is rejected. Each holds at the level's error probability, uniformly over time.
 * <p>
 * The bounds are found by bisection in floating-point arithmetic and are display values. Each is rounded outward to
 * the end of its final bisection bracket. Decisions never use them; they compare exact rationals against a frozen
 * null rate.
 */
public final class ConfidenceBounds
{
	/**
	 * Returns the lower confidence bound on a pass probability.
	 *
	 * @param passes   the number of adopted passes {@code S}
	 * @param failures the number of adopted failures {@code F}
	 * @param level    the level
	 * @return the bound, which is 0 if there are no passes
	 * @throws NullPointerException     if {@code level} is null
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative
	 */
	public static ConfidenceBound lower(int passes, int failures, SignificanceLevel level)
	{
		requireThat(passes, "passes").isNotNegative();
		requireThat(failures, "failures").isNotNegative();
		requireThat(level, "level").isNotNull();
		// Without passes, E_up never exceeds 1, so no null rate is rejected
		if (passes == 0)
			return new ConfidenceBound(0, level);

		double logThreshold = BettingProcess.log(level.threshold());
		Interval bracket = Bisection.bracket(nullRate ->
			BettingProcess.logAboveValue(nullRate, passes, failures) > logThreshold);
		return new ConfidenceBound(bracket.lower(), level);
	}

	/**
	 * Returns the upper confidence bound on a pass probability.
	 *
	 * @param passes          the number of adopted passes {@code S}
	 * @param failures        the number of adopted failures {@code F}
	 * @param level           the level
	 * @param minimumPassRate the campaign's minimum pass rate, which selects the below-minimum bets
	 * @return the bound, which is 1 if there are no failures
	 * @throws NullPointerException     if {@code level} or {@code minimumPassRate} are null
	 * @throws IllegalArgumentException if {@code passes} or {@code failures} are negative, or if
	 *                                  {@code minimumPassRate} is not strictly between 0 and 1
	 */
	public static ConfidenceBound upper(int passes, int failures, SignificanceLevel level,
		BigDecimal minimumPassRate)
	{
		requireThat(passes, "passes").isNotNegative();
		requireThat(failures, "failures").isNotNegative();
		requireThat(level, "level").isNotNull();
		requireThat(minimumPassRate, "minimumPassRate").isGreaterThan(BigDecimal.ZERO).isLessThan(BigDecimal.ONE);
		// Without failures, E_down never exceeds 1, so no null rate is rejected
		if (failures == 0)
			return new ConfidenceBound(1, level);

		double logThreshold = BettingProcess.log(level.threshold());
		Interval bracket = Bisection.bracket(nullRate ->
			BettingProcess.logBelowValue(nullRate, minimumPassRate, passes, failures) <= logThreshold);
		return new ConfidenceBound(bracket.upper(), level);
	}

	/**
	 * Prevents construction.
	 */
	private ConfidenceBounds()
	{
	}
}
