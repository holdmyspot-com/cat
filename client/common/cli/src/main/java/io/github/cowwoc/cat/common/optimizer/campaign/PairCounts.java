package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.PairTally;

/**
 * The adopted pairs of one assertion's control comparison in one cell, counted by which trials of each pair passed
 * the assertion. A pair holds a candidate formal trial and the control trial that ran the same test case on the
 * incumbent with every target prompt file emptied.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param bothPassed    the pairs in which both trials passed the assertion
 * @param candidateOnly the pairs in which only the candidate trial passed the assertion
 * @param controlOnly   the pairs in which only the control trial passed the assertion
 * @param neitherPassed the pairs in which neither trial passed the assertion
 */
public record PairCounts(int bothPassed, int candidateOnly, int controlOnly, int neitherPassed)
{
	/**
	 * No pairs.
	 */
	public static final PairCounts EMPTY = new PairCounts(0, 0, 0, 0);

	/**
	 * Creates counts.
	 *
	 * @param bothPassed    the pairs in which both trials passed the assertion
	 * @param candidateOnly the pairs in which only the candidate trial passed the assertion
	 * @param controlOnly   the pairs in which only the control trial passed the assertion
	 * @param neitherPassed the pairs in which neither trial passed the assertion
	 * @throws IllegalArgumentException if any count is negative
	 */
	public PairCounts
	{
		requireThat(bothPassed, "bothPassed").isNotNegative();
		requireThat(candidateOnly, "candidateOnly").isNotNegative();
		requireThat(controlOnly, "controlOnly").isNotNegative();
		requireThat(neitherPassed, "neitherPassed").isNotNegative();
	}

	/**
	 * Returns these counts with one more pair.
	 *
	 * @param candidatePassed {@code true} if the pair's candidate trial passed the assertion
	 * @param controlPassed   {@code true} if the pair's control trial passed the assertion
	 * @return the updated counts
	 */
	public PairCounts add(boolean candidatePassed, boolean controlPassed)
	{
		if (candidatePassed && controlPassed)
			return new PairCounts(bothPassed + 1, candidateOnly, controlOnly, neitherPassed);
		if (candidatePassed)
			return new PairCounts(bothPassed, candidateOnly + 1, controlOnly, neitherPassed);
		if (controlPassed)
			return new PairCounts(bothPassed, candidateOnly, controlOnly + 1, neitherPassed);
		return new PairCounts(bothPassed, candidateOnly, controlOnly, neitherPassed + 1);
	}

	/**
	 * Returns the number of pairs.
	 *
	 * @return the sum of the four counts
	 */
	public int pairs()
	{
		return bothPassed + candidateOnly + controlOnly + neitherPassed;
	}

	/**
	 * Returns the number of pairs whose candidate trial passed the assertion.
	 *
	 * @return {@code bothPassed + candidateOnly}
	 */
	public int candidatePasses()
	{
		return bothPassed + candidateOnly;
	}

	/**
	 * Returns the number of pairs whose control trial passed the assertion.
	 *
	 * @return {@code bothPassed + controlOnly}
	 */
	public int controlPasses()
	{
		return bothPassed + controlOnly;
	}

	/**
	 * Returns the pairs counted by the value of {@code Z = (X - Y + 1) / 2}, where {@code X} and {@code Y} are 1 when
	 * the candidate trial and the control trial pass the assertion.
	 *
	 * @return the tally that the control comparison decides from
	 */
	public PairTally tally()
	{
		return new PairTally(controlOnly, bothPassed + neitherPassed, candidateOnly);
	}
}
