package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Starts one persistent external harness process with an explicit environment.
 */
public final class HarnessProcessStarter
{
	/**
	 * Starts a harness process without inheriting arbitrary harness-specific state. A harness named without a path
	 * starts from the folders of {@code environment}'s {@code PATH}, as {@link Platform#findProgram} finds it.
	 *
	 * @param command the non-empty command and arguments
	 *
	 * @param workingDirectory the real project-copy directory
	 *
	 * @param environment the complete scrubbed process environment
	 *
	 * @param executables runs the harness's file
	 *
	 * @return the started harness process
	 *
	 * @throws IOException if the process cannot be started
	 */
	public static Process start(List<String> command, Path workingDirectory, Map<String, String> environment,
		ExecutableFiles executables) throws IOException
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(executables, "executables").isNotNull();
		List<String> resolved = new ArrayList<>(command);
		resolved.set(0, Platform.current().findProgram(command.getFirst(), environment));
		ProcessBuilder builder = new ProcessBuilder(resolved);
		builder.directory(workingDirectory.toFile());
		Map<String, String> childEnvironment = builder.environment();
		childEnvironment.clear();
		childEnvironment.putAll(environment);
		return executables.run(builder);
	}

	/**
	 * Gets the start of a started harness process, as {@link HarnessProcesses#system()} reports it.
	 *
	 * @param process the started harness process
	 *
	 * @return the process's {@link ProcessDescription#start() start}
	 *
	 * @throws NullPointerException if {@code process} is null
	 *
	 * @throws IOException if the process has ended or the operating system does not report its start
	 */
	public static String startOf(Process process) throws IOException
	{
		requireThat(process, "process").isNotNull();
		return HarnessProcesses.system().find(process.pid()).flatMap(ProcessDescription::start).orElseThrow(() ->
			new IOException("Unable to determine the harness process's start"));
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessProcessStarter()
	{
	}
}
