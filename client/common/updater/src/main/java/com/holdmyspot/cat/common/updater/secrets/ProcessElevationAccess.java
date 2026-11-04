package com.holdmyspot.cat.common.updater.secrets;

import com.holdmyspot.cat.common.updater.engine.ProcessElevation;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Exposes the POSIX check of {@link ProcessElevation} to its tests, with the program that reports the user ID.
 */
@FunctionalInterface
public interface ProcessElevationAccess
{
	/**
	 * Returns whether this process's effective user ID is 0, as a program that prints the ID reports it, counting a
	 * failed check as administrative rights.
	 *
	 * @param id        the program, run with the single argument {@code -u}
	 * @param limit     the longest time that the program may take before it is stopped
	 * @param directory a directory that the check may write short-lived files to, which is created when absent
	 * @return {@code true} if the program reports the user ID 0, or cannot be run, fails, does not end in time or prints
	 *   no user ID
	 * @throws NullPointerException if any argument is null
	 */
	boolean runsAsRoot(Path id, Duration limit, Path directory);
}
