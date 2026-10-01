package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The slow-start limit on the active candidate's concurrency.
 * <p>
 * After any adopted failure the window returns to 1. It doubles after each window whose trials all pass.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param size the current window
 * @param passes the consecutive adopted passes in the current window
 */
public record SlowStart(int size, int passes)
{
	/**
	 * The largest window, which equals the largest campaign ceiling.
	 */
	public static final int MAXIMUM_SIZE = 16;
	/**
	 * The window after a failure or a new candidate.
	 */
	public static final SlowStart INITIAL = new SlowStart(1, 0);

	/**
	 * Creates a slow-start state.
	 *
	 * @param size the current window
	 * @param passes the consecutive adopted passes in the current window
	 * @throws IllegalArgumentException if {@code size} is outside [1, {@value #MAXIMUM_SIZE}] or {@code passes} is
	 *   outside [0, size)
	 */
	public SlowStart
	{
		requireThat(size, "size").isBetween(1, true, MAXIMUM_SIZE, true);
		requireThat(passes, "passes").isBetween(0, true, size, false);
	}

	/**
	 * Returns the state after an adopted pass of the active candidate.
	 *
	 * @return the updated state
	 */
	public SlowStart afterPass()
	{
		if (passes + 1 < size)
			return new SlowStart(size, passes + 1);
		return new SlowStart(Math.min(2 * size, MAXIMUM_SIZE), 0);
	}

	/**
	 * Returns the state after an adopted failure of the active candidate.
	 *
	 * @return the initial state
	 */
	public SlowStart afterFailure()
	{
		return INITIAL;
	}
}
