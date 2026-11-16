package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;

import java.io.IOException;
import java.util.Optional;

/**
 * The operating system's processes as {@link HarnessProcesses} reports their starts, which it reads from {@code /proc}
 * on Linux and asks {@link ProcessHandle} for elsewhere.
 * <p>
 * The request server's end with its session rests on this property of the platforms, which this class is the one
 * place to correct if a platform release differs: a process's {@link ProcessDescription#start() start} is the same in
 * every process that reads it for as long as the process lives, so that the identity that the request server takes
 * from its harness ancestor stays the harness's, and the harness's end, and a later process that reuses its
 * identifier, show as {@link #isAlive(ProcessIdentity)} returning {@code false}. The server polls this rather than
 * waiting on {@link ProcessHandle#onExit()}, which on macOS reports the end of a process that is not the caller's
 * child seconds late.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ProcessHandleTable implements ProcessTable
{
	private final HarnessProcesses processes;

	/**
	 * Creates the table.
	 *
	 * @param processes reports the starts of processes
	 * @throws NullPointerException if {@code processes} is null
	 */
	public ProcessHandleTable(HarnessProcesses processes)
	{
		requireThat(processes, "processes").isNotNull();
		this.processes = processes;
	}

	@Override
	public boolean isAlive(ProcessIdentity process) throws IOException
	{
		requireThat(process, "process").isNotNull();
		Optional<ProcessDescription> description;
		try
		{
			description = processes.find(process.pid());
		}
		catch (RuntimeException e)
		{
			throw observationFailure(process, "process lookup failed", e);
		}
		if (description.isEmpty())
			return false;
		Optional<String> observedStart = description.get().start();
		if (observedStart.isEmpty() || !observedStart.get().matches(ProcessDescription.START_PATTERN))
			throw observationFailure(process, "live process start is unavailable", null);
		return observedStart.get().equals(process.start());
	}

	/**
	 * Creates a diagnostic for an identity observation that could not be completed.
	 *
	 * @param process the requested identity
	 * @param reason the observation failure
	 * @param cause the underlying failure, if any
	 * @return the contextual I/O failure
	 */
	private static IOException observationFailure(ProcessIdentity process, String reason, RuntimeException cause)
	{
		String message = "Cannot verify process identity pid=" + process.pid() + ", recorded start=" +
			process.start() + ", observed start unavailable: " + reason;
		if (cause == null)
			return new IOException(message);
		return new IOException(message, cause);
	}
}
