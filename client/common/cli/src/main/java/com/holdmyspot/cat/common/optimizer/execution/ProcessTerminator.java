package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.runner.policy.ProcessDescription;

/**
 * Terminates a process that a run manifest recorded, for the {@link Driver}'s reconciliation.
 */
@FunctionalInterface
public interface ProcessTerminator
{
	/**
	 * Indicates whether a live process matches the recorded identifier and {@link ProcessDescription#start() start}
	 * and was asked to terminate. Requests termination of that process and its descendants. Does not request
	 * termination of the current process or a process whose start cannot be verified.
	 *
	 * @param pid the recorded process identifier
	 * @param start the recorded start of the process
	 * @return {@code true} if a matching live process was asked to terminate; {@code false} if no live process with
	 *   that identifier has the start {@code start}, its start cannot be verified, or it is the current process
	 * @throws NullPointerException if {@code start} is null
	 * @throws IllegalArgumentException if {@code pid} is not positive
	 */
	boolean terminate(long pid, String start);
}
