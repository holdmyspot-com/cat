package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.runner.policy.ProcessDescription;

/**
 * Checks and terminates a process only when its recorded identity still matches.
 * <p>
 * A process is identified by its identifier and its {@link ProcessDescription#start() start}. Text that does not
 * have the form of a start, {@link ProcessDescription#START_PATTERN}, identifies no process.
 */
public interface HarnessProcessLiveness
{
	/**
	 * Indicates whether a process with an exact start remains alive.
	 *
	 * @param pid the positive process identifier
	 *
	 * @param start the process's start
	 *
	 * @return {@code true} if the matching process is alive
	 */
	boolean isAlive(long pid, String start);

	/**
	 * Requests termination of a process with an exact start.
	 *
	 * @param pid the positive process identifier
	 *
	 * @param start the process's start
	 *
	 * @return {@code true} if a matching live process accepted termination
	 */
	boolean terminate(long pid, String start);
}
