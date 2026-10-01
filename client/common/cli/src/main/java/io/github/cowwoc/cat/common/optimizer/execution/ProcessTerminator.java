package io.github.cowwoc.cat.common.optimizer.execution;

import java.time.Instant;

/**
 * Terminates a process that a run manifest recorded, for the {@link Driver}'s reconciliation.
 */
@FunctionalInterface
public interface ProcessTerminator
{
	/**
	 * Terminates a process and its descendants if the process is still the one that was recorded.
	 *
	 * @param pid the recorded process identifier
	 * @param startedAt the recorded start time of the process
	 * @return {@code true} if a matching live process was asked to terminate; {@code false} if no live process with
	 *   that identifier started at {@code startedAt}
	 * @throws NullPointerException if {@code startedAt} is null
	 * @throws IllegalArgumentException if {@code pid} is not positive
	 */
	boolean terminate(long pid, Instant startedAt);
}
