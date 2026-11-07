package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.cli.internal.ProcessHarnessCli;
import java.nio.file.Path;

/**
 * Exposes a controlled process start for tests of {@link ProcessHarnessCli}.
 */
@FunctionalInterface
public interface ProcessHarnessCliAccess
{
	/**
	 * Creates a runner that starts the harness program through the supplied starter instead of the operating system.
	 *
	 * @param command the recorded harness program and its environment
	 * @param home    the harness home
	 * @param starter starts the program that a process builder describes
	 * @return the runner
	 * @throws NullPointerException if any of the arguments are null
	 */
	ProcessHarnessCli create(HarnessCommand command, Path home, ProcessHarnessCli.Starter starter);
}
