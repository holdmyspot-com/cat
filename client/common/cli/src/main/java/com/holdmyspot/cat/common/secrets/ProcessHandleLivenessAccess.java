package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;

import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Exposes controlled process lookup for tests of {@link ProcessHandleLiveness}.
 */
@FunctionalInterface
public interface ProcessHandleLivenessAccess
{
	/**
	 * Creates a checker that finds processes through the supplied lookups instead of the operating system's process
	 * table.
	 *
	 * @param processes reports the start of a running process
	 * @param handles   finds the process with an identifier, or nothing if no such process exists
	 * @return the checker
	 * @throws NullPointerException if any of the arguments are null
	 */
	ProcessHandleLiveness create(HarnessProcesses processes, LongFunction<Optional<ProcessHandle>> handles);
}
