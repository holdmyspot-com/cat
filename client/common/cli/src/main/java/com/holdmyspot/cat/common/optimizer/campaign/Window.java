package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The concurrency window of one unit of work: how many of its trials may run at once.
 * <p>
 * The window doubles, up to the campaign's ceiling, after each full window of adopted trials that all passed. A
 * failure that consumes one of the unit's spare failures lowers it to the spare failures that remain plus one; any
 * other failure keeps it, but starts a new full window.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param size the most trials of the unit that may run at once
 * @param passes the adopted passes since the window last changed or a failure was adopted
 */
public record Window(int size, int passes)
{
	/**
	 * Creates a window.
	 *
	 * @param size the most trials of the unit that may run at once
	 * @param passes the adopted passes since the window last changed or a failure was adopted
	 * @throws IllegalArgumentException if {@code size} is outside [1, {@value Campaign#MAXIMUM_CEILING}] or
	 *   {@code passes} is outside [0, size)
	 */
	public Window
	{
		requireThat(size, "size").isBetween(1, true, Campaign.MAXIMUM_CEILING, true);
		requireThat(passes, "passes").isBetween(0, true, size, false);
	}

	/**
	 * Returns the window of a unit that starts with a number of spare failures: the spare failures plus one, up to
	 * the ceiling.
	 *
	 * @param spare the further failures that the unit can absorb before the next one decides it
	 * @param ceiling the campaign's ceiling
	 * @return the window
	 * @throws IllegalArgumentException if {@code spare} is negative or {@code ceiling} is outside
	 *   [1, {@value Campaign#MAXIMUM_CEILING}]
	 */
	public static Window start(int spare, int ceiling)
	{
		requireThat(spare, "spare").isNotNegative();
		requireThat(ceiling, "ceiling").isBetween(1, true, Campaign.MAXIMUM_CEILING, true);
		return new Window((int) Math.min(ceiling, spare + 1L), 0);
	}

	/**
	 * Returns the window after an adopted pass.
	 *
	 * @param ceiling the campaign's ceiling
	 * @return the updated window
	 */
	public Window afterPass(int ceiling)
	{
		if (passes + 1 < size)
			return new Window(size, passes + 1);
		return new Window(Math.max(size, Math.min(2 * size, ceiling)), 0);
	}

	/**
	 * Returns the window after an adopted failure that consumed one of the unit's spare failures.
	 *
	 * @param spare the spare failures that remain
	 * @return the updated window
	 * @throws IllegalArgumentException if {@code spare} is negative
	 */
	public Window afterConsumingFailure(int spare)
	{
		requireThat(spare, "spare").isNotNegative();
		return new Window((int) Math.min(size, spare + 1L), 0);
	}

	/**
	 * Returns the window after an adopted failure that consumed none of the unit's spare failures.
	 *
	 * @return the window of the same size, which needs a new full window of passes to grow
	 */
	public Window afterOtherFailure()
	{
		return new Window(size, 0);
	}
}
