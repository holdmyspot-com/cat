package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.cli.internal.InstallerProcess;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessHarnessCli;
import java.util.Map;

/**
 * Exposes a controlled process start for tests of the installer runs that {@link InstallerProcess} makes.
 */
@FunctionalInterface
public interface InstallerProcessAccess
{
	/**
	 * Creates a runner that starts the installer launcher through the supplied starter instead of the operating
	 * system.
	 *
	 * @param platform    the running platform
	 * @param environment the installer's environment, which the launcher gets
	 * @param starter     starts the program that a process builder describes
	 * @return the runner
	 * @throws NullPointerException if any of the arguments are null
	 */
	InstallerProcess create(Platform platform, Map<String, String> environment, ProcessHarnessCli.Starter starter);
}
