package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The adopted pairs of one assertion's paired comparison, counted by the value of
 * {@code Z = (X - Y + 1) / 2}, where {@code X} and {@code Y} are 1 when the candidate trial and the reference trial
 * of the pair pass the assertion.
 *
 * @param referenceAhead the number of pairs with {@code Z = 0}: only the reference trial passed
 * @param even           the number of pairs with {@code Z = 1/2}: both trials passed or both failed
 * @param candidateAhead the number of pairs with {@code Z = 1}: only the candidate trial passed
 */
public record PairTally(int referenceAhead, int even, int candidateAhead)
{
	/**
	 * No pairs.
	 */
	public static final PairTally EMPTY = new PairTally(0, 0, 0);

	/**
	 * Creates a tally.
	 *
	 * @param referenceAhead the number of pairs with {@code Z = 0}: only the reference trial passed
	 * @param even           the number of pairs with {@code Z = 1/2}: both trials passed or both failed
	 * @param candidateAhead the number of pairs with {@code Z = 1}: only the candidate trial passed
	 * @throws IllegalArgumentException if any count is negative
	 */
	public PairTally
	{
		requireThat(referenceAhead, "referenceAhead").isNotNegative();
		requireThat(even, "even").isNotNegative();
		requireThat(candidateAhead, "candidateAhead").isNotNegative();
	}

	/**
	 * Returns the number of pairs.
	 *
	 * @return the sum of the three counts
	 */
	public int pairs()
	{
		return referenceAhead + even + candidateAhead;
	}
}
