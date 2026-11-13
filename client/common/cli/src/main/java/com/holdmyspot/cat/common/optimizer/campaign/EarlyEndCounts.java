package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
	 * Counts completed trials and the subset that ended early.
 *
 * @param completed the number of completed trials
 * @param endedEarly the number of completed trials that ended early
 */
public record EarlyEndCounts(long completed, long endedEarly)
{
	/** No completed trials. */
	public static final EarlyEndCounts EMPTY = new EarlyEndCounts(0, 0);

	/** Creates validated counts. */
	public EarlyEndCounts
	{
		requireThat(completed, "completed").isNotNegative();
		requireThat(endedEarly, "endedEarly").isBetween(0L, true, completed, true);
	}

	/** Adds one completed trial. */
	public EarlyEndCounts add(boolean endedEarly)
	{
		long updatedEarly = this.endedEarly;
		if (endedEarly)
			updatedEarly += 1;
		return new EarlyEndCounts(completed + 1, updatedEarly);
	}
}
