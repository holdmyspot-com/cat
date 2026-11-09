package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.cli.internal.InstallerProcess;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessHarnessCli;
import java.nio.file.Path;
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
	default InstallerProcess create(Platform platform, Map<String, String> environment,
		ProcessHarnessCli.Starter starter)
	{
		return create(platform, environment, starter, Path.of(System.getProperty("java.home")));
	}

	/**
	 * Creates a runner that starts the installer launcher through the supplied starter instead of the operating
	 * system, and that finds the runtime's program that starts processes in the supplied runtime folder.
	 *
	 * @param platform    the running platform
	 * @param environment the installer's environment, which the launcher gets
	 * @param starter     starts the program that a process builder describes
	 * @param runtime     the Java runtime folder of the running installer
	 * @return the runner
	 * @throws NullPointerException if any of the arguments are null
	 */
	InstallerProcess create(Platform platform, Map<String, String> environment, ProcessHarnessCli.Starter starter,
		Path runtime);
}
