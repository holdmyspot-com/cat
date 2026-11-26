package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * What the host shows of one process of a run's process tree.
 *
 * @param processId the process ID
 * @param program   the path of the program that the process runs, or empty if the host does not show it
 * @param cpuTime   the processor time that the process has used, or empty if the host does not show it
 */
public record ProcessSnapshot(long processId, Optional<Path> program, Optional<Duration> cpuTime)
{
	/**
	 * Creates a snapshot.
	 *
	 * @param processId the process ID
	 * @param program   the path of the program that the process runs, or empty if the host does not show it
	 * @param cpuTime   the processor time that the process has used, or empty if the host does not show it
	 * @throws NullPointerException if {@code program} or {@code cpuTime} are null
	 */
	public ProcessSnapshot
	{
		requireThat(program, "program").isNotNull();
		requireThat(cpuTime, "cpuTime").isNotNull();
	}

	/**
	 * Reads what the host shows of a process.
	 *
	 * @param process the process
	 * @return the snapshot
	 * @throws NullPointerException if {@code process} is null
	 */
	public static ProcessSnapshot of(ProcessHandle process)
	{
		requireThat(process, "process").isNotNull();
		ProcessHandle.Info info = process.info();
		return new ProcessSnapshot(process.pid(), info.command().map(Path::of), info.totalCpuDuration());
	}
}
