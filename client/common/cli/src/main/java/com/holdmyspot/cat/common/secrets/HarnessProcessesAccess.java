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
	 * Returns the processes that the operating system reports, with the command lines and the starts of Linux
	 * processes read from a process folder.
	 *
	 * @param processFolder the folder that stands for Linux's {@code /proc}, or null to take the arguments and the
	 *                      starts from {@link ProcessHandle}
	 * @return the processes
	 */
	HarnessProcesses system(Path processFolder);
}
