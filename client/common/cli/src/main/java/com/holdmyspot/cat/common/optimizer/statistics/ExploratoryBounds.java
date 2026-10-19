package com.holdmyspot.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Exploratory bounds that guide development and concurrency. They are not simultaneous guarantees and support no
 * claim.
 */
public final class ExploratoryBounds
{
	/**
	 * The standard normal quantile for a two-sided 95% interval.
	 */
	private static final double WILSON_Z = 1.959_963_984_540_054;
	/**
	 * The error probability of the one-sided Clopper&ndash;Pearson bound.
	 */
	private static final double CLOPPER_PEARSON_ERROR = 0.05;

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
	 * Returns the one-sided 95% Clopper&ndash;Pearson upper bound on an event probability: the probability
	 * {@code u} at which observing at most {@code events} in {@code trials} has probability 0.05.
	 *
	 * @param events the number of events, such as failures
	 * @param trials the number of trials
	 * @return the bound, rounded up; 1 if there are no trials or every trial is an event
	 * @throws IllegalArgumentException if {@code trials} is negative, or if {@code events} is negative or greater than
	 *                                  {@code trials}
	 */
	public static double clopperPearsonUpper(int events, int trials)
	{
		requireThat(trials, "trials").isNotNegative();
		requireThat(events, "events").isBetween(0, true, trials, true);
		if (events == trials)
			return 1;

		return Bisection.bracket(probability ->
			binomialDistribution(events, trials, probability) > CLOPPER_PEARSON_ERROR).upper();
	}

	/**
	 * Returns the probability of at most {@code events} events in {@code trials} independent trials, computed from
	 * logarithms so that large trial counts do not underflow.
	 *
	 * @param events      the largest number of events
	 * @param trials      the number of trials
	 * @param probability the event probability, strictly between 0 and 1
	 * @return the cumulative probability
	 */
	private static double binomialDistribution(int events, int trials, double probability)
	{
		double logOdds = Math.log(probability) - Math.log1p(-probability);
		double[] logTerms = new double[events + 1];
		logTerms[0] = trials * Math.log1p(-probability);
		double largest = logTerms[0];
		for (int count = 1; count <= events; ++count)
		{
			// C(n, j) / C(n, j - 1) = (n - j + 1) / j
			logTerms[count] = logTerms[count - 1] + Math.log((double) (trials - count + 1) / count) + logOdds;
			largest = Math.max(largest, logTerms[count]);
		}
		double sum = 0;
		for (double logTerm : logTerms)
			sum += Math.exp(logTerm - largest);
		return Math.exp(largest + Math.log(sum));
	}

	/**
	 * Prevents construction.
	 */
	private ExploratoryBounds()
	{
	}
}
