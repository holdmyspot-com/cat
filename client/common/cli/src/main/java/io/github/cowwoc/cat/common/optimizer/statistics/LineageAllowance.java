package io.github.cowwoc.cat.common.optimizer.statistics;

import java.math.BigInteger;

/**
 * Allocates the false-certification budget across candidates in one lineage.
 */
public final class LineageAllowance
{
	private static final BigInteger TWENTY = BigInteger.valueOf(20);

	/**
	 * Prevents instantiation of this utility class.
	 */
	private LineageAllowance()
	{
	}

	/**
	 * Returns the allowance for a one-based candidate index.
	 *
	 * @param candidateIndex the one-based candidate index
	 * @return the candidate's alpha allowance
	 */
	public static RationalProbability forCandidate(int candidateIndex)
	{
		if (candidateIndex <= 0)
			throw new IllegalArgumentException("candidateIndex must be positive");

		BigInteger index = BigInteger.valueOf(candidateIndex);
		return new RationalProbability(BigInteger.ONE, TWENTY.multiply(index).multiply(index.add(BigInteger.ONE)));
	}
}
