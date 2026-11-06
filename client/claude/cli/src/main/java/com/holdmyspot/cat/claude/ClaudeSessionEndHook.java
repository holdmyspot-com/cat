package com.holdmyspot.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.HookScope;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.Pause;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Deletes the rules files of a session's CAT session directory after Claude ends the session. A resumed session's
 * SessionStart writes them again; the rest of the directory stays until the sweep removes it.
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
		run(args, System.in, System.err, Platform.current().environment(System.getenv()),
			Path.of(System.getProperty("user.dir")), Pause::sleeping);
	}

	/**
	 * Processes one Claude SessionEnd event and deletes the rules files of its session.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input non-null harness hook input
	 * @param error non-null diagnostic output
	 * @param environment a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @param waiting how the hook waits between the attempts of a retried operation
	 * @throws NullPointerException if any argument is null
	 */
	public static void run(String[] args, InputStream input, PrintStream error, Map<String, String> environment,
		Path workingDirectory, Waiting waiting)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		try (HookScope scope = ClaudeHookScope.open(environment, waiting))
		{
			if (args.length != 0)
				throw new IllegalArgumentException("SessionEnd accepts no arguments");
			JsonNode event = scope.readEvent("SessionEnd", input);
			Path projectDir = ClaudeHookScope.projectDir(scope, event, environment, workingDirectory);
			scope.sessionDirectory(projectDir, event, "session_id").deleteRulesFiles();
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
