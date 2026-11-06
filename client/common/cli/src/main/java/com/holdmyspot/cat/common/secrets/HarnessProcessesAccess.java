package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import java.nio.file.Path;

/**
 * Exposes the system's process lookup with a chosen process folder, for tests of {@link HarnessProcesses#system()}.
 */
@FunctionalInterface
public interface HarnessProcessesAccess
{
	/**
	 * Returns the processes that the operating system reports, with the command lines of Linux processes read from a
	 * process folder.
	 *
	 * @param commandLineFolder the folder that holds the {@code <pid>/cmdline} files, or null to take the arguments
	 *                          from {@link ProcessHandle}
	 * @return the processes
	 */
	HarnessProcesses system(Path commandLineFolder);
}
