package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import org.apache.commons.statistics.interval.BinomialConfidenceInterval;
import org.apache.commons.statistics.interval.Interval;

/**
 * Calculates the exploratory 95% Wilson interval for representative development outcomes.
 */
public final class DevelopmentWilsonInterval
{
	private static final BigDecimal DEFAULT_CONFIDENCE_LEVEL = new BigDecimal("0.95");

	/**
	 * Prevents construction of the interval utility.
	 */
	private DevelopmentWilsonInterval()
	{
	}

	/**
	 * Calculates the two-sided 95% Wilson interval for completed representative outcomes.
	 *
	 * @param counts at least one completed outcome for one candidate and category
	 * @return lower and upper exploratory compliance bounds
	 * @throws NullPointerException if {@code counts} is null
	 * @throws IllegalArgumentException if {@code counts} has no completed outcomes
	 */
	public static Interval calculate(CategoryCounts counts)
	{
		return calculate(counts, DEFAULT_CONFIDENCE_LEVEL);
	}

	/**
	 * Calculates an exploratory Wilson interval at the confirmed development confidence level.
	 *
	 * @param counts at least one completed representative outcome for one candidate and category
	 * @param confidenceLevel the confirmed two-sided coverage level strictly between zero and one
	 * @return lower and upper exploratory compliance bounds
	 * @throws NullPointerException if the {@code counts} or {@code confidenceLevel} inputs are null
	 * @throws IllegalArgumentException if {@code counts} is empty or {@code confidenceLevel} is outside (0, 1)
	 */
	public static Interval calculate(CategoryCounts counts, BigDecimal confidenceLevel)
	{
		requireThat(counts, "counts").isNotNull();
		requireThat(confidenceLevel, "confidenceLevel").isGreaterThan(BigDecimal.ZERO).
			isLessThan(BigDecimal.ONE);
		if (counts.trials() == 0)
			throw new IllegalArgumentException("Wilson interval requires a completed outcome");

		double errorRate = BigDecimal.ONE.subtract(confidenceLevel).doubleValue();
		return BinomialConfidenceInterval.WILSON_SCORE.fromErrorRate(counts.trials(), counts.successes(), errorRate);
	}
}
