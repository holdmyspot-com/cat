package com.holdmyspot.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One-sided 95% Clopper–Pearson upper bounds on a rate, which guide the choice of a development judge. They support
 * no claim.
 */
public final class ClopperPearson
{
	/**
	 * The one-sided error level of the 95% bound.
	 */
	private static final double ERROR_LEVEL = 0.05;

	/**
	 * Indicates whether the one-sided 95% upper bound on a rate is at most a limit. The bound is at most {@code limit}
	 * exactly when {@code events} or fewer events would occur in at most 5% of {@code trials} trials whose rate is
	 * {@code limit}.
	 *
	 * @param events the number of trials with the event
	 * @param trials the number of trials
	 * @param limit  the largest acceptable upper bound
	 * @return {@code true} if the upper bound is at most {@code limit}
	 * @throws IllegalArgumentException if {@code trials} is not positive, {@code events} is negative or greater than
	 *                                  {@code trials}, or {@code limit} is not strictly between 0 and 1
	 */
	public static boolean upperBoundAtMost(int events, int trials, double limit)
	{
		requireThat(trials, "trials").isPositive();
		requireThat(events, "events").isBetween(0, true, trials, true);
		requireThat(limit, "limit").isBetween(0.0, false, 1.0, false);
		double term = Math.pow(1 - limit, trials);
		double cumulative = term;
		double odds = limit / (1 - limit);
		for (int event = 0; event < events; ++event)
		{
			term *= (trials - event) / (double) (event + 1) * odds;
			cumulative += term;
		}
		return cumulative <= ERROR_LEVEL;
	}

	/**
	 * Prevents construction.
	 */
	private ClopperPearson()
	{
	}
}
