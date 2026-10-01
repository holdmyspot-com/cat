package io.github.cowwoc.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/**
 * Injects CAT rules into a Claude SessionStart context.
 *
	 * <p>
	 * Unlike the other harness hooks, Claude stores the complete context in a CAT-owned file and injects only its
	 * location. When Claude Code receives more than 10,000 characters of hook context, it writes that context to a long
	 * internal path and asks the model to {@code Read} it. In a runner project, the generated path includes a sanitized
	 * {@code -runs-claude-...} segment. The model can change that segment to {@code -runs/claude-...} in its
	 * {@code Read} request, making the requested file nonexistent and leaving the complete rules unread. The short
	 * CAT-owned path avoids Claude Code's spill mechanism and lets the model read the complete rules before acting.
 */
public final class ClaudeSessionStartHook
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
		run(args, System.in, System.out, System.err, System.getenv(), Path.of(System.getProperty("user.dir")));
	}

	/**
	 * Processes one Claude SessionStart event and writes CAT's hook response.
	 *
	 * @param args a non-null empty command-line argument array
	 * @param input non-null harness hook input
	 * @param output non-null harness hook output
	 * @param error non-null diagnostic output
	 * @param environment a non-null process environment
	 * @param workingDirectory a non-null process working directory
	 * @throws NullPointerException if {@code args}, {@code input}, {@code output}, {@code error},
	 *         {@code environment}, or {@code workingDirectory} are null
	 */
	public static void run(String[] args, InputStream input, OutputStream output, PrintStream error,
		Map<String, String> environment, Path workingDirectory)
	{
		requireThat(args, "args").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(error, "error").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(workingDirectory, "workingDirectory").isNotNull();
		ClaudeRuleInjectionHook.run("SessionStart", args, input, output, error, environment, workingDirectory);
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionStartHook()
	{
	}
}
