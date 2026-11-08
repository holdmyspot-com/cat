package com.holdmyspot.cat.common.runner.launcher;

import java.util.List;
import java.util.Optional;

/**
 * The operating system's processes as the request server sees them. A process that has ended, or whose identifier a
 * later process now uses, is absent from every answer.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
public interface ProcessTable
{
	/**
	 * Returns every live process.
	 *
	 * @return the processes, in no particular order
	 */
	List<ProcessIdentity> all();

	/**
	 * Returns the live descendants of a process: its children, their children, and so on.
	 *
	 * @param process the process
	 * @return the descendants, in no particular order; empty if {@code process} is not alive
	 * @throws NullPointerException if {@code process} is null
	 */
	List<ProcessIdentity> descendants(ProcessIdentity process);

	/**
	 * Returns the parent of a process.
	 *
	 * @param process the process
	 * @return the live parent, or empty if {@code process} is not alive or has no live parent
	 * @throws NullPointerException if {@code process} is null
	 */
	Optional<ProcessIdentity> parent(ProcessIdentity process);

	/**
	 * Returns the arguments of a process's command line.
	 *
	 * @param process the process
	 * @return the arguments that follow the executable, or empty if {@code process} is not alive or its command line
	 *   cannot be read
	 * @throws NullPointerException if {@code process} is null
	 */
	Optional<List<String>> arguments(ProcessIdentity process);

	/**
	 * Indicates whether a process is alive.
	 *
	 * @param process the process
	 * @return {@code true} if a live process has the identifier and the start instant of {@code process}
	 * @throws NullPointerException if {@code process} is null
	 */
	boolean isAlive(ProcessIdentity process);
}
