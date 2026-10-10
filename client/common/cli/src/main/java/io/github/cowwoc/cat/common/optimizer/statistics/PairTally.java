package io.github.cowwoc.cat.common.optimizer.statistics;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The adopted pairs of one assertion's control comparison, counted by the value of
 * {@code Z = (X - Y + 1) / 2}, where {@code X} and {@code Y} are 1 when the candidate trial and the control trial of
 * the pair pass the assertion.
 *
 * @param controlAhead   the number of pairs with {@code Z = 0}: only the control trial passed
 * @param even           the number of pairs with {@code Z = 1/2}: both trials passed or both failed
 * @param candidateAhead the number of pairs with {@code Z = 1}: only the candidate trial passed
 */
public record PairTally(int controlAhead, int even, int candidateAhead)
{
	/**
	 * No pairs.
	 */
	public static final PairTally EMPTY = new PairTally(0, 0, 0);

	/**
	 * Creates a tally.
	 *
	 * @param controlAhead   the number of pairs with {@code Z = 0}: only the control trial passed
	 * @param even           the number of pairs with {@code Z = 1/2}: both trials passed or both failed
	 * @param candidateAhead the number of pairs with {@code Z = 1}: only the candidate trial passed
	 * @throws IllegalArgumentException if any count is negative
	 */
	public PairTally
	{
		requireThat(controlAhead, "controlAhead").isNotNegative();
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
		return controlAhead + even + candidateAhead;
	}
}
