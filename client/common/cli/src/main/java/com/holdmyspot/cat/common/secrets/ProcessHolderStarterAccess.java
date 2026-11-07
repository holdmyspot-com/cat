package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.ExecutableFiles.ProcessLauncher;
import com.holdmyspot.cat.common.runner.launcher.client.HolderStarter;
import com.holdmyspot.cat.common.runner.launcher.client.ProcessHolderStarter;

/**
 * Exposes controlled process starts for tests of {@link ProcessHolderStarter}.
 */
@FunctionalInterface
public interface ProcessHolderStarterAccess
{
	/**
	 * Creates a starter that starts each configured holder process through the supplied launcher instead of the
	 * operating system.
	 *
	 * @param launcher starts the configured holder process
	 * @return the starter
	 * @throws NullPointerException if {@code launcher} is null
	 */
	HolderStarter create(ProcessLauncher launcher);
}
