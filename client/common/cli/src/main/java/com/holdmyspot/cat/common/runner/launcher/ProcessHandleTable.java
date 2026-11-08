package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The operating system's processes as {@link ProcessHandle} reports them, with parents from
 * {@link ProcessHandle#parent()}, and with command lines and starts from {@link HarnessProcesses}, which reads
 * {@code /proc} on Linux and asks {@link ProcessHandle} elsewhere, where Windows reports no command lines.
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
	 * @param processes reports the command lines and starts of processes
	 * @throws NullPointerException if {@code processes} is null
	 */
	public ProcessHandleTable(HarnessProcesses processes)
	{
		requireThat(processes, "processes").isNotNull();
		this.processes = processes;
	}

	@Override
	public List<ProcessIdentity> all()
	{
		return identities(ProcessHandle.allProcesses());
	}

	@Override
	public List<ProcessIdentity> descendants(ProcessIdentity process)
	{
		requireThat(process, "process").isNotNull();
		if (find(process).isEmpty())
			return List.of();
		return ProcessHandle.of(process.pid()).map(handle -> identities(handle.descendants())).orElse(List.of());
	}

	@Override
	public Optional<ProcessIdentity> parent(ProcessIdentity process)
	{
		requireThat(process, "process").isNotNull();
		if (find(process).isEmpty())
			return Optional.empty();
		return ProcessHandle.of(process.pid()).flatMap(ProcessHandle::parent).map(ProcessHandle::pid).
			flatMap(this::identity);
	}

	@Override
	public Optional<List<String>> arguments(ProcessIdentity process)
	{
		requireThat(process, "process").isNotNull();
		return find(process).flatMap(ProcessDescription::arguments);
	}

	@Override
	public boolean isAlive(ProcessIdentity process)
	{
		requireThat(process, "process").isNotNull();
		return find(process).isPresent();
	}

	/**
	 * Finds the live process that has an identity.
	 *
	 * @param process the identity
	 * @return the process with the identifier of {@code process} whose start matches, or empty if there is none
	 */
	private Optional<ProcessDescription> find(ProcessIdentity process)
	{
		// The start tells the process apart from a later one that reuses its identifier
		return processes.find(process.pid()).
			filter(description -> description.start().filter(process.start()::equals).isPresent());
	}

	/**
	 * Returns the identities of the processes that have a start.
	 *
	 * @param handles the processes
	 * @return their identities
	 */
	private List<ProcessIdentity> identities(Stream<ProcessHandle> handles)
	{
		return handles.map(ProcessHandle::pid).map(this::identity).flatMap(Optional::stream).toList();
	}

	/**
	 * Returns the identity of a process.
	 *
	 * @param pid the process's identifier
	 * @return its identity, or empty if it has no positive identifier, has ended or its start cannot be read
	 */
	private Optional<ProcessIdentity> identity(long pid)
	{
		if (pid <= 0)
			return Optional.empty();
		return processes.find(pid).flatMap(description -> description.start().
			map(start -> new ProcessIdentity(pid, start)));
	}
}
