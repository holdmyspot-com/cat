package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.cowwoc.cat.common.ExecutableFiles;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Starts one persistent external harness process with an explicit environment.
 */
public final class HarnessProcessStarter
{
	/**
	 * Starts a harness process without inheriting arbitrary harness-specific state.
	 *
	 * @param command the non-empty command and arguments
	 *
	 * @param workingDirectory the real project-copy directory
	 *
	 * @param environment the complete scrubbed process environment
	 *
	 * @return the started harness process
	 *
	 * @throws IOException if the process cannot be started
	 */
	public static Process start(List<String> command, Path workingDirectory, Map<String, String> environment)
		throws IOException
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(workingDirectory.toFile());
		Map<String, String> childEnvironment = builder.environment();
		childEnvironment.clear();
		childEnvironment.putAll(environment);
		return ExecutableFiles.run(builder);
	}

	/**
	 * Gets the stable start marker for a started native process.
	 *
	 * @param process the started native process
	 *
	 * @return the process start marker
	 *
	 * @throws NullPointerException if {@code process} is null
	 *
	 * @throws IOException if the operating system does not expose a start marker
	 */
	public static Instant startedAt(Process process) throws IOException
	{
		requireThat(process, "process").isNotNull();
		return process.toHandle().info().startInstant().orElseThrow(() ->
			new IOException("Unable to determine the native process start time"));
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessProcessStarter()
	{
	}
}
