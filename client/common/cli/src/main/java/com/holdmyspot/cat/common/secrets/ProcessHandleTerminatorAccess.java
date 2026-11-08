package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator;
import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator.ProcessLookup;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;

/**
 * Exposes controlled process lookup for tests of {@link ProcessHandleTerminator}.
 */
@FunctionalInterface
public interface ProcessHandleTerminatorAccess
{
	/**
	 * Creates a terminator using the supplied process lookups.
	 *
	 * @param starts    reports the start of a running process
	 * @param processes finds recorded processes and identifies the driver
	 * @return the terminator
	 * @throws NullPointerException if any of the arguments are null
	 */
	ProcessHandleTerminator create(HarnessProcesses starts, ProcessLookup processes);
}
