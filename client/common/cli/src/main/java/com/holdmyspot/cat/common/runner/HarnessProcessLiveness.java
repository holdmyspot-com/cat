package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.runner.policy.ProcessDescription;

import java.io.IOException;

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
	 * @return {@code true} if the exact process is alive, or {@code false} if it is absent, ended, or replaced
	 * @throws IOException if the process is present but its start cannot be established
	 */
	boolean isAlive(long pid, String start) throws IOException;

	/**
	 * Requests termination of a process with an exact start.
	 *
	 * @param pid the positive process identifier
	 *
	 * @param start the process's start
	 *
	 * @return {@code true} if a termination request was accepted, or {@code false} if the process is absent, ended, or
	 *   replaced; a successful request does not establish that the process exited
	 * @throws IOException if the process is present but its start cannot be established, or termination cannot be
	 *   requested
	 */
	boolean terminate(long pid, String start) throws IOException;
}
