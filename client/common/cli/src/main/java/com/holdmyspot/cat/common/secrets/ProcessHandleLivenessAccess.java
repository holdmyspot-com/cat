package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;

import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Exposes controlled process lookup for tests of {@link ProcessHandleLiveness}.
 */
@FunctionalInterface
public interface ProcessHandleLivenessAccess
{
	/**
	 * Creates a checker that finds processes through the supplied lookup instead of the operating system's process
	 * table.
	 *
	 * @param processes finds the process with an identifier, or nothing if no such process exists
	 * @return the checker
	 * @throws NullPointerException if {@code processes} is null
	 */
	ProcessHandleLiveness create(LongFunction<Optional<ProcessHandle>> processes);
}
