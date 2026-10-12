package io.github.cowwoc.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.HookScope;
import io.github.cowwoc.cat.common.ToolScope;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Deletes CAT rule-context files after Claude ends a session.
 */
public final class ClaudeSessionEndHook
{
	/**
	 * Runs the harness SessionEnd hook.
	 *
	 * @param args a non-null empty command-line argument array
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		run(args, System.in, System.err, System.getenv(), Path.of(System.getProperty("user.dir")));
	}

	/**
	 * Processes one Claude SessionEnd event and deletes its owned context files.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input non-null harness hook input
	 * @param error non-null diagnostic output
	 * @param environment a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @throws NullPointerException if any argument is null
	 */
	public static void run(String[] args, InputStream input, PrintStream error, Map<String, String> environment,
		Path workingDirectory)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		try (HookScope scope = ClaudeHookScope.open(environment))
		{
			if (args.length != 0)
				throw new IllegalArgumentException("SessionEnd accepts no arguments");
			JsonNode event = scope.readEvent("SessionEnd", input);
			Path projectDir = ClaudeHookScope.projectDir(scope, event, environment, workingDirectory);
			try (ToolScope toolScope = ToolScope.forWorkingDirectory(projectDir))
			{
				ClaudeSessionContext.delete(toolScope.catTemp(), event);
			}
		}
		catch (IOException | IllegalArgumentException | JacksonException e)
		{
			error.println("CAT SessionEnd: " + e.getMessage());
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionEndHook()
	{
	}
}
