package com.holdmyspot.cat.claude.policy;

import java.util.List;
import java.util.Optional;

/**
 * Finds running harness processes.
 */
@FunctionalInterface
public interface HarnessProcesses
{
	/**
	 * Returns the processes that the operating system reports.
	 *
	 * @return the processes
	 */
	static HarnessProcesses system()
	{
		return processId -> ProcessHandle.of(processId).
			filter(ProcessHandle::isAlive).
			map(handle ->
			{
				ProcessHandle.Info info = handle.info();
				return new HarnessProcess(processId, info.startInstant(), info.command(),
					info.arguments().map(List::of));
			});
	}

	/**
	 * Returns a running process.
	 *
	 * @param processId the process ID
	 * @return the process, or empty if no process with that ID is running
	 */
	Optional<HarnessProcess> find(long processId);
}
