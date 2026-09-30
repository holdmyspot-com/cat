package io.github.cowwoc.cat.common.optimizer.statistics;

/**
 * Computes one-sided exact binomial lower confidence bounds.
 */
public final class BinomialLowerBound
{
	/**
	 * Prevents instantiation of this utility class.
	 */
	private BinomialLowerBound()
	{
	}

	/**
	 * Computes a Clopper-Pearson lower confidence bound from an exact allowance.
	 *
	 * @param successes the number of observed successes
	 * @param trials the number of observed trials
	 * @param alpha the one-sided false-positive allowance
	 * @return the greatest binary floating-point value that does not exceed the exact lower bound
	 * @throws NullPointerException if {@code alpha} is null
	 * @throws IllegalArgumentException if the observations or allowance are invalid
	 */
	public static double oneSided(int successes, int trials, RationalProbability alpha)
	{
		validate(successes, trials, alpha);
		if (successes == 0)
			return 0.0;

		long lowerBits = Double.doubleToRawLongBits(0.0);
		long upperBits = Double.doubleToRawLongBits(1.0);
		while (upperBits - lowerBits > 1)
		{
			long midpointBits = lowerBits + (upperBits - lowerBits) / 2;
			RationalProbability midpoint = RationalProbability.fromDoubleExact(Double.longBitsToDouble(midpointBits));
			if (compareUpperTail(successes, trials, midpoint, alpha) <= 0)
				lowerBits = midpointBits;
			else
				upperBits = midpointBits;
		}
		return Double.longBitsToDouble(lowerBits);
	}

	/**
	 * Indicates whether the exact lower bound strictly exceeds a proposed threshold.
	 *
	 * @param successes the number of observed successes
	 * @param trials the number of observed trials
	 * @param alpha the exact one-sided false-positive allowance
	 * @param threshold the exact probability the lower bound must exceed
	 * @return {@code true} if the exact binomial upper tail at {@code threshold} is below {@code alpha}
	 * @throws NullPointerException if {@code alpha} or {@code threshold} are null
	 * @throws IllegalArgumentException if the observations, allowance, or threshold are invalid
	 */
	static boolean strictlyExceeds(int successes, int trials, RationalProbability alpha,
		RationalProbability threshold)
	{
		validate(successes, trials, alpha);
		if (threshold == null)
			throw new NullPointerException("threshold");
		return successes > 0 && compareUpperTail(successes, trials, threshold, alpha) < 0;
	}

	/**
	 * Validates observations and their exact false-positive allowance.
	 *
	 * @param successes the number of observed successes
	 * @param trials the number of observed trials
	 * @param alpha the exact allowance
	 */
	private static void validate(int successes, int trials, RationalProbability alpha)
	{
		if (trials <= 0)
			throw new IllegalArgumentException("trials must be positive");
		if (successes < 0 || successes > trials)
			throw new IllegalArgumentException("successes must be between zero and trials");
		if (alpha == null)
			throw new NullPointerException("alpha");
		if (alpha.numerator().signum() <= 0 ||
			alpha.numerator().compareTo(alpha.denominator()) >= 0)
		{
			throw new IllegalArgumentException("alpha must be between zero and one");
		}
	}

	/**
	 * Compares the exact upper-tail probability at a threshold with an exact allowance.
	 *
	 * @param successes the first term of the inclusive upper tail
	 * @param trials the number of Bernoulli trials
	 * @param threshold the exact success probability
	 * @param alpha the exact allowance
	 * @return negative, zero, or positive as the upper tail is below, equal to, or above {@code alpha}
	 */
	private static int compareUpperTail(int successes, int trials, RationalProbability threshold,
		RationalProbability alpha)
	{
		BinomialTail tail = new BinomialTail(trials, threshold);
		for (int cutoff = 0; cutoff < successes; ++cutoff)
			tail.advance();
		return tail.compareTo(alpha);
	}
}
