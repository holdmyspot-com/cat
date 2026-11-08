package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.ProcessHandleLivenessSecrets;

import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Checks whether a Java {@link ProcessHandle} still identifies the process recorded for a harness execution. The
 * process's start comes from {@link HarnessProcesses#system()}, whose start every process that reads it agrees on.
 */
public final class ProcessHandleLiveness implements HarnessProcessLiveness
{
	static
	{
		ProcessHandleLivenessSecrets.setProcessHandleLivenessAccess(ProcessHandleLiveness::new);
	}

	/**
	 * Reports the start of a running process.
	 */
	private final HarnessProcesses processes;
	/**
	 * Finds a process of the operating system's process table by its identifier.
	 */
	private final LongFunction<Optional<ProcessHandle>> handles;

	/**
	 * Creates a checker of the operating system's processes.
	 */
	public ProcessHandleLiveness()
	{
		this(HarnessProcesses.system(), ProcessHandle::of);
	}

	/**
	 * Creates a checker that finds processes through the supplied lookups.
	 *
	 * @param processes reports the start of a running process
	 * @param handles   finds the process with an identifier, or nothing if no such process exists
	 * @throws NullPointerException if any of the arguments are null
	 */
	private ProcessHandleLiveness(HarnessProcesses processes, LongFunction<Optional<ProcessHandle>> handles)
	{
		requireThat(processes, "processes").isNotNull();
		requireThat(handles, "handles").isNotNull();
		this.processes = processes;
		this.handles = handles;
	}

	@Override
	public boolean isAlive(long pid, String start)
	{
		assert pid > 0;
		assert start != null;
		return matchingProcess(pid, start).isPresent();
	}

	@Override
	public boolean terminate(long pid, String start)
	{
		assert pid > 0;
		assert start != null;
		return matchingProcess(pid, start).map(ProcessHandle::destroy).orElse(false);
	}

	/**
	 * Finds a live process only if both its identifier and start match.
	 *
	 * @param pid   the positive process identifier
	 * @param start the process's start
	 * @return the matching process, if present
	 */
	private Optional<ProcessHandle> matchingProcess(long pid, String start)
	{
		assert pid > 0;
		assert start != null;
		if (processes.find(pid).flatMap(ProcessDescription::start).filter(start::equals).isEmpty())
			return Optional.empty();
		return handles.apply(pid).filter(ProcessHandle::isAlive);
	}
}
