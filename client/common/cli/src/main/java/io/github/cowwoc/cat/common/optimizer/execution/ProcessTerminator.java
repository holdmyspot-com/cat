package io.github.cowwoc.cat.common.optimizer.execution;

import java.time.Instant;

/**
 * Terminates a process that a run manifest recorded, for the {@link Driver}'s reconciliation.
 */
@FunctionalInterface
public interface ProcessTerminator
{
	/**
	 * Indicates whether a live process matches the recorded identifier and start time and was asked to terminate.
	 * Requests termination of that process and its descendants. Does not request termination of the current process
	 * or a process whose start time cannot be verified.
	 *
	 * @param pid the recorded process identifier
	 * @param startedAt the recorded start time of the process
	 * @return {@code true} if a matching live process was asked to terminate; {@code false} if no live process with
	 *   that identifier started at {@code startedAt}, its start time cannot be verified, or it is the current process
	 * @throws NullPointerException if {@code startedAt} is null
	 * @throws IllegalArgumentException if {@code pid} is not positive
	 */
	boolean terminate(long pid, Instant startedAt);
}
