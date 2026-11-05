package com.holdmyspot.cat.common.runner.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	 * Returns the processes that started a process, the nearest first.
	 *
	 * @param process the process
	 * @return the ancestors of {@code process}, starting with its parent
	 * @throws NullPointerException if {@code process} is null
	 */
	static List<HarnessProcess> ancestorsOf(ProcessHandle process)
	{
		requireThat(process, "process").isNotNull();
		List<HarnessProcess> ancestors = new ArrayList<>();
		Optional<ProcessHandle> parent = process.parent();
		while (parent.isPresent())
		{
			ProcessHandle handle = parent.get();
			ProcessHandle.Info info = handle.info();
			ancestors.add(new HarnessProcess(handle.pid(), info.startInstant(), info.command(),
				info.arguments().map(List::of)));
			parent = handle.parent();
		}
		return ancestors;
	}

	/**
	 * Returns a running process.
	 *
	 * @param processId the process ID
	 * @return the process, or empty if no process with that ID is running
	 */
	Optional<HarnessProcess> find(long processId);
}
