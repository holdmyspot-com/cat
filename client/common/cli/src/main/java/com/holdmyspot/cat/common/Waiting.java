package com.holdmyspot.cat.common;

import java.time.Duration;

/**
 * Creates the pauses that retrying collaborators wait with.
 * <p>
 * Only an application entry point supplies the waiting that blocks the calling thread ({@code Pause::sleeping}).
 * Every other caller receives its waiting from that entry point or from its scope, so that tests supply waiting that
 * does not block.
 */
@FunctionalInterface
public interface Waiting
{
	/**
	 * Returns a pause that waits for an interval.
	 *
	 * @param interval how long each call waits
	 * @return the pause
	 * @throws NullPointerException if {@code interval} is null
	 * @throws IllegalArgumentException if {@code interval} is not positive
	 */
	Pause every(Duration interval);
}
