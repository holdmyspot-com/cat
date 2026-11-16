package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.ProcessHandleLivenessSecrets;

import java.io.IOException;
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
	public boolean isAlive(long pid, String start) throws IOException
	{
		assert pid > 0;
		assert start != null;
		return matchingProcess(pid, start).isPresent();
	}

	@Override
	public boolean terminate(long pid, String start) throws IOException
	{
		assert pid > 0;
		assert start != null;
		Optional<ProcessHandle> process = matchingProcess(pid, start);
		if (process.isEmpty())
			return false;
		try
		{
			return process.get().destroy();
		}
		catch (SecurityException e)
		{
			throw observationFailure(pid, start, "termination was denied", e);
		}
	}

	/**
	 * Finds a live process only if both its identifier and start match.
	 *
	 * @param pid   the positive process identifier
	 * @param start the process's start
	 * @return the matching process, if present
	 * @throws IOException if a live process's start or identity cannot be established
	 */
	private Optional<ProcessHandle> matchingProcess(long pid, String start) throws IOException
	{
		assert pid > 0;
		assert start != null;
		Optional<ProcessDescription> description;
		try
		{
			description = processes.find(pid);
		}
		catch (RuntimeException e)
		{
			throw observationFailure(pid, start, "process lookup failed", e);
		}
		if (description.isEmpty())
			return Optional.empty();
		Optional<String> observedStart = description.get().start();
		if (observedStart.isEmpty() || !observedStart.get().matches(ProcessDescription.START_PATTERN))
			throw observationFailure(pid, start, "live process start is unavailable", null);
		if (!observedStart.get().equals(start))
			return Optional.empty();
		try
		{
			Optional<ProcessHandle> handle = handles.apply(pid);
			if (handle.isEmpty() || handle.get().pid() != pid || !handle.get().isAlive())
				return Optional.empty();
			return handle;
		}
		catch (RuntimeException e)
		{
			throw observationFailure(pid, start, "process handle lookup failed", e);
		}
	}

	/**
	 * Creates a contextual failure for an identity that cannot be safely observed.
	 *
	 * @param pid the recorded process identifier
	 * @param start the recorded process start
	 * @param reason the failed observation
	 * @param cause the underlying failure, if any
	 * @return the contextual failure
	 */
	private static IOException observationFailure(long pid, String start, String reason, RuntimeException cause)
	{
		String message = "Cannot verify process identity pid=" + pid + ", recorded start=" + start +
			", observed start unavailable: " + reason;
		if (cause == null)
			return new IOException(message);
		return new IOException(message, cause);
	}
}
