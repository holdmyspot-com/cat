package io.github.cowwoc.cat.common.runner;

import java.time.Instant;


/**
 * Checks and terminates a process only when its recorded identity still matches.
 */
public interface HarnessProcessLiveness
{
	/**
	 * Determines whether a process with an exact start marker remains alive.
	 *
	 * @param pid the positive process identifier
	 *
	 * @param startedAt the process start marker
	 *
	 * @return {@code true} if the matching process is alive
	 */
	boolean isAlive(long pid, Instant startedAt);

	/**
	 * Requests termination of a process with an exact start marker.
	 *
	 * @param pid the positive process identifier
	 *
	 * @param startedAt the process start marker
	 *
	 * @return {@code true} if a matching live process accepted termination
	 */
	boolean terminate(long pid, Instant startedAt);
}
