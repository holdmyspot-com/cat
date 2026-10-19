package com.holdmyspot.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigInteger;

/**
 * The evidence that one statistical decision requires: the decision is made when its e-value exceeds
 * {@code threshold}.
 * <p>
 * By Ville's inequality, a valid e-value process ever exceeds {@code threshold} with probability at most
 * {@code 1 / threshold}, so the level's error probability is {@code 1 / threshold}. Every campaign level is the
 * reciprocal of an integer, which lets decisions compare exact integers.
 *
 * @param threshold the value that an e-value must exceed
 */
public record SignificanceLevel(BigInteger threshold)
{
	/**
	 * Creates a level.
	 *
	 * @param threshold the value that an e-value must exceed
	 * @throws NullPointerException     if {@code threshold} is null
	 * @throws IllegalArgumentException if {@code threshold} is not greater than 1
	 */
	public SignificanceLevel
	{
		requireThat(threshold, "threshold").isGreaterThan(BigInteger.ONE);
	}

	/**
	 * Returns the error probability for display.
	 *
	 * @return {@code 1 / threshold}, rounded to the nearest {@code double}
	 */
	public double probability()
	{
		return 1.0 / threshold.doubleValue();
	}
}
