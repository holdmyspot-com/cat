package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;


/**
 * Runs one harness CLI command with a caller-supplied working directory and environment, then captures its output.
 */
@FunctionalInterface
public interface HarnessProcessExecutor
{
	/**
	 * Executes one native command.
	 *
	 * @param command the command and arguments
	 *
	 * @param workingDirectory the command working directory
	 *
	 * @param environment the complete scrubbed environment
	 *
	 * @return the captured process result
	 *
	 * @throws IOException if the process cannot be started or captured
	 */
	HarnessProcessResult execute(List<String> command, Path workingDirectory, Map<String, String> environment)
		throws IOException;
}
