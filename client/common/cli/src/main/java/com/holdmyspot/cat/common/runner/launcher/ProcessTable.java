package com.holdmyspot.cat.common.runner.launcher;

/**
 * The operating system's processes as the request server sees them. A process that has ended, or whose identifier a
 * later process now uses, is not alive.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
@FunctionalInterface
public interface ProcessTable
{
	/**
	 * Indicates whether a process is alive.
	 *
	 * @param process the process
	 * @return {@code true} if a live process has the identifier and the start instant of {@code process}
	 * @throws NullPointerException if {@code process} is null
	 */
	boolean isAlive(ProcessIdentity process);
}
