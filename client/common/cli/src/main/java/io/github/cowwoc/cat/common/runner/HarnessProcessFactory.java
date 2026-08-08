package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


/**
 * Starts a harness CLI process with a caller-supplied command, working directory, and environment.
 */
@FunctionalInterface
public interface HarnessProcessFactory
{
	/**
	 * Starts one native harness process.
	 *
	 * @param command the command and arguments
	 *
	 * @param workingDirectory the process working directory
	 *
	 * @param environment the complete scrubbed environment
	 *
	 * @return the live native process
	 *
	 * @throws IOException if the process cannot be started
	 */
	HarnessProcess start(List<String> command, Path workingDirectory, Map<String, String> environment)
		throws IOException;
}
