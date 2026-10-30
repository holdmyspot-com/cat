package com.holdmyspot.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.Pause;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * Implements the Codex SessionStart hook protocol for CAT project rules.
 */
public final class CodexSessionStartHook
{
	/**
	 * Runs the harness SessionStart hook.
	 *
	 * @param args a non-null empty command-line argument array
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		run(args, System.in, System.out, System.err, Platform.current().environment(System.getenv()),
			Path.of(System.getProperty("user.dir")), Pause::sleeping);
	}

	/**
	 * Processes one Codex SessionStart event and writes CAT's hook response.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input non-null harness hook input
	 * @param output non-null harness hook output
	 * @param error non-null diagnostic output
	 * @param environment a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @param waiting how the hook waits between the attempts of a retried operation
	 * @throws NullPointerException if {@code args}, {@code input}, {@code output}, {@code error},
	 *         {@code environment}, {@code workingDirectory} or {@code waiting} are null
	 */
	public static void run(String[] args, InputStream input, OutputStream output, PrintStream error,
											 Map<String, String> environment, Path workingDirectory, Waiting waiting)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		CodexRuleInjectionHook.run("SessionStart", args, input, output, error, environment, workingDirectory, waiting);
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexSessionStartHook()
	{
	}
}
