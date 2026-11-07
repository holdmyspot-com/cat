package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.ExecutableFiles.ProcessLauncher;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart.DetachedStarter;

/**
 * Exposes controlled process starts for tests of {@link SessionLauncherStart#PROCESSES}.
 */
@FunctionalInterface
public interface SessionLauncherStartAccess
{
	/**
	 * Returns a starter that configures each process as {@link SessionLauncherStart#PROCESSES} does and starts it
	 * through the supplied launcher instead of the operating system.
	 *
	 * @param launcher starts each configured process
	 * @return the starter
	 * @throws NullPointerException if {@code launcher} is null
	 */
	DetachedStarter processes(ProcessLauncher launcher);
}
