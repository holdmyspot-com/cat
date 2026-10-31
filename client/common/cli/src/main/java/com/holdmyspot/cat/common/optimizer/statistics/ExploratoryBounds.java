package com.holdmyspot.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exploratory bounds that guide development. They are not simultaneous guarantees and support no claim.
 */
public final class ExploratoryBounds
{
	/**
	 * The standard normal quantile for a two-sided 95% interval.
	 */
	private static final double WILSON_Z = 1.959_963_984_540_054;

	/**
	 * Returns the two-sided 95% Wilson score interval for a pass probability.
	 *
	 * @param passes the number of passes
	 * @param trials the number of trials
	 * @return the interval, clamped to {@code [0, 1]}, or {@code [0, 1]} if there are no trials
	 * @throws IllegalArgumentException if {@code trials} is negative, or if {@code passes} is negative or greater than
	 *                                  {@code trials}
	 */
	public static Interval wilson(int passes, int trials)
	{
		requireThat(trials, "trials").isNotNegative();
		requireThat(passes, "passes").isBetween(0, true, trials, true);
		if (trials == 0)
			return new Interval(0, 1);

		double count = trials;
		double rate = passes / count;
		double zSquared = WILSON_Z * WILSON_Z;
		double denominator = 1 + zSquared / count;
		double center = (rate + zSquared / (2 * count)) / denominator;
		double halfWidth = WILSON_Z / denominator *
			Math.sqrt(rate * (1 - rate) / count + zSquared / (4 * count * count));
		return new Interval(Math.max(0, center - halfWidth), Math.min(1, center + halfWidth));
	}

	/**
	 * Prevents construction.
	 */
	private ExploratoryBounds()
	{
	}
}
