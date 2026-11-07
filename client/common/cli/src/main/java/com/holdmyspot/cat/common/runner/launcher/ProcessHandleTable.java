package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The operating system's processes as {@link ProcessHandle} reports them, with command lines from
 * {@link HarnessProcesses}, which reads {@code /proc/<pid>/cmdline} on Linux and asks {@link ProcessHandle} elsewhere,
 * where Windows reports none, and parents from {@link ProcessHandle#parent()}.
 * <p>
 * The session launcher's requester proof rests on these properties of the platforms and harnesses, which this class
 * is the one place to correct if a platform or harness release differs:
 * <ul>
 *   <li>a holder that a sandboxed command starts descends from the harness process, through the sandbox's own
 *     processes, so that the launcher finds it among {@link #descendants(ProcessIdentity)};</li>
 *   <li>the holder's command line is readable from outside the sandbox, a PID namespace included, whatever
 *     its length;</li>
 *   <li>a process's start instant stays the same for as long as it lives, so that the harness's end, and a later
 *     process that reuses its identifier, show as {@link #isAlive(ProcessIdentity)} returning {@code false}. The
 *     launcher polls this rather than waiting on {@link ProcessHandle#onExit()}, which on macOS reports the end of a
 *     process that is not the caller's child seconds late.</li>
 * </ul>
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ProcessHandleTable implements ProcessTable
{
	private final HarnessProcesses processes;

	/**
	 * Creates the table.
	 *
	 * @param processes reports the command lines of processes
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
		return find(process).map(handle -> identities(handle.descendants())).orElse(List.of());
	}

	@Override
	public Optional<ProcessIdentity> parent(ProcessIdentity process)
	{
		requireThat(process, "process").isNotNull();
		return find(process).flatMap(ProcessHandle::parent).flatMap(ProcessHandleTable::identity);
	}

	@Override
	public Optional<List<String>> arguments(ProcessIdentity process)
	{
		requireThat(process, "process").isNotNull();
		// The start instant tells the process apart from a later one that reuses its identifier
		return processes.find(process.pid()).
			filter(description -> description.start().
				map(start -> new ProcessIdentity(process.pid(), start)).
				filter(process::equals).
				isPresent()).
			flatMap(ProcessDescription::arguments);
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
	 * @return the process with the identifier of {@code process} whose start instant matches, or empty if there is
	 *   none
	 */
	private static Optional<ProcessHandle> find(ProcessIdentity process)
	{
		return ProcessHandle.of(process.pid()).filter(ProcessHandle::isAlive).
			filter(handle -> identity(handle).filter(process::equals).isPresent());
	}

	/**
	 * Returns the identities of the processes that have a start instant.
	 *
	 * @param handles the processes
	 * @return their identities
	 */
	private static List<ProcessIdentity> identities(Stream<ProcessHandle> handles)
	{
		return handles.map(ProcessHandleTable::identity).flatMap(Optional::stream).toList();
	}

	/**
	 * Returns the identity of a process.
	 *
	 * @param handle the process
	 * @return its identity, or empty if it has no positive identifier or its start instant cannot be read
	 */
	private static Optional<ProcessIdentity> identity(ProcessHandle handle)
	{
		if (handle.pid() <= 0)
			return Optional.empty();
		return handle.info().startInstant().map(start -> new ProcessIdentity(handle.pid(), start));
	}
}
