package com.holdmyspot.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A closed interval of real numbers, for display.
 *
 * @param lower the lower endpoint
 * @param upper the upper endpoint
 */
public record Interval(double lower, double upper)
{
	/**
	 * Creates an interval.
	 *
	 * @param lower the lower endpoint
	 * @param upper the upper endpoint
	 * @throws IllegalArgumentException if {@code lower} is greater than {@code upper}, or if either endpoint is not
	 *                                  finite
	 */
	public Interval
	{
		requireThat(lower, "lower").isFinite().isLessThanOrEqualTo(upper, "upper");
		requireThat(upper, "upper").isFinite();
	}
}
