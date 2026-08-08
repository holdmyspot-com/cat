package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * Injects CAT rules addressed to a Codex subagent at the start of its conversation.
 */
public final class CodexSubagentStartHook
{
	/**
	 * Runs the native SubagentStart hook.
	 *
	 * @param args a non-null empty command-line argument array
	 * @throws NullPointerException if {@code args} is null
	 */
	public static void main(String[] args)
	{
		requireThat(args, "args").isNotNull();
		run(args, System.in, System.out, System.err, System.getenv(), Path.of(System.getProperty("user.dir")));
	}

	/**
	 * Processes one Codex SubagentStart event and writes CAT's hook response.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input non-null native hook input
	 * @param output non-null native hook output
	 * @param error non-null diagnostic output
	 * @param environment a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @throws NullPointerException if any argument is null
	 */
	public static void run(String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory)
	{
		CodexRuleInjectionHook.run("SubagentStart", args, input, output, error, environment, workingDirectory);
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexSubagentStartHook()
	{
	}
}
