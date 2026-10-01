package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A one-sided confidence bound on a pass probability, labelled with the level at which it holds.
 *
 * @param value the bound, between 0 and 1
 * @param level the level whose test the bound inverts
 */
public record ConfidenceBound(double value, SignificanceLevel level)
{
	/**
	 * Creates a bound.
	 *
	 * @param value the bound, between 0 and 1
	 * @param level the level whose test the bound inverts
	 * @throws NullPointerException     if {@code level} is null
	 * @throws IllegalArgumentException if {@code value} is not between 0 and 1
	 */
	public ConfidenceBound
	{
		requireThat(value, "value").isBetween(0.0, true, 1.0, true);
		requireThat(level, "level").isNotNull();
	}
}
