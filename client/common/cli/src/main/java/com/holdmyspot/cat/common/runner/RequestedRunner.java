package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.JvmScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Starts the runner launcher of a resolved artifact, which runs the artifact that holds it, and redirects its
 * diagnostics into the broker's private response directory.
 *
 * @param scope    the borrowed process scope, which the caller closes
 * @param launcher the runner launcher in the artifact's {@code bin} directory
 * @param prefix   command arguments preceding runner options
 */
public record RequestedRunner(JvmScope scope, Path launcher, List<String> prefix)
{
	/**
	 * Creates a runner target.
	 *
	 * @param scope    the borrowed process scope, which the caller closes
	 * @param launcher the runner launcher in the artifact's {@code bin} directory
	 * @param prefix   command arguments preceding runner options
	 */
	public RequestedRunner
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		prefix = List.copyOf(prefix);
	}

	/**
	 * Reads a target from an artifact resolver's scalar result.
	 *
	 * @param scope the borrowed process scope
	 * @param result the resolver's {@code artifact=} and {@code runner=} lines
	 * @param prefix command arguments preceding runner options
	 * @return the target
	 * @throws IOException if either required location is missing
	 */
	public static RequestedRunner resolved(JvmScope scope, String result, List<String> prefix) throws IOException
	{
		return new RequestedRunner(scope, locations(result).get(1), prefix);
	}

	/**
	 * Reads the locations in an artifact resolver's scalar result.
	 *
	 * @param result the resolver's {@code artifact=} and {@code runner=} lines
	 * @return the artifact, then its runner launcher
	 * @throws NullPointerException if {@code result} is null
	 * @throws IOException          if either required location is missing
	 */
	static List<Path> locations(String result) throws IOException
	{
		requireThat(result, "result").isNotNull();
		Path artifact = null;
		Path runner = null;
		for (String line : result.lines().toList())
		{
			if (line.startsWith("artifact="))
				artifact = Path.of(line.substring("artifact=".length()));
			if (line.startsWith("runner="))
				runner = Path.of(line.substring("runner=".length()));
		}
		if (artifact == null || runner == null)
			throw new IOException("Artifact resolver returned no artifact or runner location");
		return List.of(artifact, runner);
	}

	/**
	 * Starts the child runner with a host environment and file-backed diagnostics.
	 *
	 * @param arguments runner options selected by the broker
	 * @param directory the response directory
	 * @param environment the host environment, never the session command environment
	 * @return the child runner
	 * @throws IOException if the runner cannot start
	 */
	public Process start(List<String> arguments, Path directory, Map<String, String> environment) throws IOException
	{
		requireThat(arguments, "arguments").isNotNull();
		requireThat(directory, "directory").isDirectory();
		requireThat(environment, "environment").isNotNull();
		List<String> command = new ArrayList<>();
		command.add(launcher.toString());
		command.addAll(prefix);
		command.addAll(arguments);
		ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).
			redirectOutput(directory.resolve("runner.stdout").toFile()).
			redirectError(directory.resolve(RequestedSessionStarter.ERROR_FILE).toFile());
		builder.environment().clear();
		builder.environment().putAll(environment);
		return scope.executableFiles().run(builder);
	}
}
