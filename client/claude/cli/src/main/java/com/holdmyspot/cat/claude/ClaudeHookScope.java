package com.holdmyspot.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.HookScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Translates Claude's hook environment at the harness boundary.
 */
final class ClaudeHookScope
{
	/**
	 * Prevents instantiation.
	 */
	private ClaudeHookScope()
	{
	}

	/**
	 * Opens a scope for one Claude hook invocation.
	 *
	 * @param environment the non-null harness hook environment
	 * @return the scope
	 * @throws IOException if Claude did not supply a usable plugin root or home directory, or if a CAT variable
	 *   conflicts with the plugin root
	 */
	static HookScope open(Map<String, String> environment) throws IOException
	{
		return HookScope.fromEnvironment(environment, "Claude", "CLAUDE_PLUGIN_ROOT",
			Platform.current().homeVariable(), ClaudeSessionHarness.HARNESS);
	}

	/**
	 * Resolves the project directory of a Claude session: the directory that Claude started in, which Claude passes to
	 * hooks as {@code CLAUDE_PROJECT_DIR}. The event's {@code cwd} follows each {@code cd} that the agent runs, so it
	 * locates the project only when Claude does not supply the variable.
	 *
	 * @param scope the hook's scope
	 * @param event the hook event
	 * @param environment the hook environment
	 * @param workingDirectory the hook process's working directory
	 * @return the normalized project directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalStateException if {@code scope} is closed
	 * @throws IOException if the resolved project directory is not a directory
	 */
	static Path projectDir(HookScope scope, JsonNode event, Map<String, String> environment, Path workingDirectory)
		throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(event, "event").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		String launchDirectory = environment.getOrDefault("CLAUDE_PROJECT_DIR", "");
		if (launchDirectory.isEmpty())
			return scope.projectDir(event, workingDirectory);
		Path result = Path.of(launchDirectory).toAbsolutePath().normalize();
		if (!Files.isDirectory(result))
			throw new IOException("CLAUDE_PROJECT_DIR is not a directory: " + result);
		return result;
	}
}
