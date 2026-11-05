package com.holdmyspot.cat.common.runner.policy;

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
	 * Returns the processes that the operating system reports. On Linux, their arguments come from the process
	 * folder {@code /proc}, because {@link ProcessHandle} reports none for a command line longer than 4 KiB.
	 *
	 * @return the processes
	 */
	static HarnessProcesses system()
	{
		return SystemProcesses.current();
	}

	/**
	 * Returns the processes that started a process, the nearest first, as {@link #system()} reports them.
	 *
	 * @param process the process
	 * @return the ancestors of {@code process}, starting with its parent
	 * @throws NullPointerException if {@code process} is null
	 */
	static List<HarnessProcess> ancestorsOf(ProcessHandle process)
	{
		requireThat(process, "process").isNotNull();
		return SystemProcesses.current().ancestryOf(process);
	}

	/**
	 * Returns a running process.
	 *
	 * @param processId the process ID
	 * @return the process, or empty if no process with that ID is running
	 */
	Optional<HarnessProcess> find(long processId);
}
