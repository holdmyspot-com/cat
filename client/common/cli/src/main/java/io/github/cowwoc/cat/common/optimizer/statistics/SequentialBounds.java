package io.github.cowwoc.cat.common.optimizer.statistics;

/**
 * Directional anytime-valid limits for one category and attempt.
 *
 * @param lower the one-sided lower confidence bound
 * @param upper the one-sided upper confidence bound
 */
public record SequentialBounds(double lower, double upper)
{
	/**
	 * Validates the displayed probability limits.
	 *
	 * @param lower the one-sided lower confidence bound
	 * @param upper the one-sided upper confidence bound
	 * @throws IllegalArgumentException if the endpoints are not ordered finite probabilities
	 */
	public SequentialBounds
	{
		if (!Double.isFinite(lower) || !Double.isFinite(upper) ||
			lower < 0 || upper > 1 || lower > upper)
			throw new IllegalArgumentException("Directional bounds must lie in increasing probability order");
	}
}
