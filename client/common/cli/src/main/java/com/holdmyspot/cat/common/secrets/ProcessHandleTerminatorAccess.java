package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator;
import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator.ProcessLookup;

/**
 * Exposes controlled process lookup for tests of {@link ProcessHandleTerminator}.
 */
@FunctionalInterface
public interface ProcessHandleTerminatorAccess
{
	/**
	 * Creates a terminator using the supplied process lookup.
	 *
	 * @param processes finds recorded processes and identifies the driver
	 * @return the terminator
	 * @throws NullPointerException if {@code processes} is null
	 */
	ProcessHandleTerminator create(ProcessLookup processes);
}
