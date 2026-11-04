package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.installer.engine.ProcessElevation;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Exposes the checks of {@link ProcessElevation} to its tests, with the program that reports the user ID or the access
 * token's groups.
 */
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

	/**
	 * Returns whether this process's access token is elevated, as a program that lists the token's groups reports it,
	 * counting a failed check as administrative rights.
	 *
	 * @param whoami    the program, run with the arguments {@code /groups /fo csv /nh}
	 * @param limit     the longest time that the program may take before it is stopped
	 * @param directory a directory that the check may write short-lived files to, which is created when absent
	 * @return {@code true} if the program reports an integrity level of high or above, or cannot be run, fails, does
	 *   not end in time or reports no integrity level
	 * @throws NullPointerException if any argument is null
	 */
	boolean holdsElevatedToken(Path whoami, Duration limit, Path directory);
}
