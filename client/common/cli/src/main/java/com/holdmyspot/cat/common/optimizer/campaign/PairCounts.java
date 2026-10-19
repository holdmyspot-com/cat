package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.statistics.PairTally;

/**
 * The adopted pairs of one assertion's paired comparison in one cell, counted by which trials of each pair passed
 * the assertion. A pair holds a candidate formal trial and the reference trial that ran the same test case: a control
 * trial or an incumbent trial.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param bothPassed    the pairs in which both trials passed the assertion
 * @param candidateOnly the pairs in which only the candidate trial passed the assertion
 * @param referenceOnly the pairs in which only the reference trial passed the assertion
 * @param neitherPassed the pairs in which neither trial passed the assertion
 */
public record PairCounts(int bothPassed, int candidateOnly, int referenceOnly, int neitherPassed)
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
	 * @param referenceOnly the pairs in which only the reference trial passed the assertion
	 * @param neitherPassed the pairs in which neither trial passed the assertion
	 * @throws IllegalArgumentException if any count is negative
	 */
	public PairCounts
	{
		requireThat(bothPassed, "bothPassed").isNotNegative();
		requireThat(candidateOnly, "candidateOnly").isNotNegative();
		requireThat(referenceOnly, "referenceOnly").isNotNegative();
		requireThat(neitherPassed, "neitherPassed").isNotNegative();
	}

	/**
	 * Returns these counts with one more pair.
	 *
	 * @param candidatePassed {@code true} if the pair's candidate trial passed the assertion
	 * @param referencePassed {@code true} if the pair's reference trial passed the assertion
	 * @return the updated counts
	 */
	public PairCounts add(boolean candidatePassed, boolean referencePassed)
	{
		if (candidatePassed && referencePassed)
			return new PairCounts(bothPassed + 1, candidateOnly, referenceOnly, neitherPassed);
		if (candidatePassed)
			return new PairCounts(bothPassed, candidateOnly + 1, referenceOnly, neitherPassed);
		if (referencePassed)
			return new PairCounts(bothPassed, candidateOnly, referenceOnly + 1, neitherPassed);
		return new PairCounts(bothPassed, candidateOnly, referenceOnly, neitherPassed + 1);
	}

	/**
	 * Returns the number of pairs.
	 *
	 * @return the sum of the four counts
	 */
	public int pairs()
	{
		return bothPassed + candidateOnly + referenceOnly + neitherPassed;
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
	 * Returns the number of pairs whose reference trial passed the assertion.
	 *
	 * @return {@code bothPassed + referenceOnly}
	 */
	public int referencePasses()
	{
		return bothPassed + referenceOnly;
	}

	/**
	 * Returns the pairs counted by the value of {@code Z = (X - Y + 1) / 2}, where {@code X} and {@code Y} are 1 when
	 * the candidate trial and the reference trial pass the assertion.
	 *
	 * @return the tally that the paired comparison decides from
	 */
	public PairTally tally()
	{
		return new PairTally(referenceOnly, bothPassed + neitherPassed, candidateOnly);
	}
}
