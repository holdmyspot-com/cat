package com.holdmyspot.cat.common.installer.cli.internal;

import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import java.nio.file.Path;

/**
 * Creates the runner of a recorded harness program.
 */
@FunctionalInterface
public interface HarnessCliFactory
{
	/**
	 * Creates the runner.
	 *
	 * @param command the recorded harness program and its environment
	 * @param home    the harness home, which is the program's working directory
	 * @return the runner
	 */
	HarnessCli create(HarnessCommand command, Path home);
}
