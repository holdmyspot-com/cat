package io.github.cowwoc.cat.common.optimizer.statistics;

import java.math.BigInteger;

/**
 * Advances exact binomial upper tails through increasing success cutoffs for one fixed sample.
 */
final class BinomialTail
{
	private final int trials;
	private final BigInteger successNumerator;
	private final BigInteger failureNumerator;
	private final BigInteger denominator;
	private BigInteger coefficient = BigInteger.ONE;
	private BigInteger successPower = BigInteger.ONE;
	private BigInteger failurePower;
	private BigInteger tailNumerator;
	private int successes;

	/**
	 * Creates the tail distribution for a fixed number of trials.
	 *
	 * @param trials the positive number of Bernoulli trials
	 * @param successProbability the exact probability of one success
	 */
	BinomialTail(int trials, RationalProbability successProbability)
	{
		if (trials <= 0)
			throw new IllegalArgumentException("trials must be positive");
		if (successProbability == null)
			throw new NullPointerException("successProbability");

		this.trials = trials;
		this.successNumerator = successProbability.numerator();
		this.failureNumerator = successProbability.denominator().subtract(successNumerator);
		this.denominator = successProbability.denominator().pow(trials);
		this.failurePower = failureNumerator.pow(trials);
		this.tailNumerator = denominator;
	}

	/**
	 * Removes the next lower outcome and advances the inclusive upper-tail cutoff by one success.
	 */
	void advance()
	{
		if (successes == trials)
			throw new IllegalStateException("The cutoff exceeds the number of trials");
		if (successNumerator.signum() == 0)
		{
			tailNumerator = BigInteger.ZERO;
			++successes;
			return;
		}
		if (failureNumerator.signum() == 0)
		{
			++successes;
			return;
		}

		tailNumerator = tailNumerator.subtract(coefficient.multiply(successPower).multiply(failurePower));
		coefficient = coefficient.multiply(BigInteger.valueOf(trials - successes)).
			divide(BigInteger.valueOf(successes + 1L));
		successPower = successPower.multiply(successNumerator);
		failurePower = failurePower.divide(failureNumerator);
		++successes;
	}

	/**
	 * Compares the current inclusive upper-tail probability with another exact probability.
	 *
	 * @param probability the comparison probability
	 * @return negative, zero, or positive as the tail is below, equal to, or above {@code probability}
	 */
	int compareTo(RationalProbability probability)
	{
		if (probability == null)
			throw new NullPointerException("probability");
		return tailNumerator.multiply(probability.denominator()).compareTo(
			probability.numerator().multiply(denominator));
	}
}
