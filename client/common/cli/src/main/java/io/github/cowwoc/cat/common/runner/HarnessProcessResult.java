package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * Captures a harness CLI command's exit code, terminal-turn state, and redacted standard-output and standard-error
 * text.
 *
 * @param exitCode the harness process exit code
 *
 * @param standardOutput captured standard output
 *
 * @param standardError captured standard error
 *
 * @param completed whether the harness emitted the terminal event for the requested turn
 *
 * @param assistantResponse the harness-specific adapter's extracted final assistant response
 *
 * @param assistantMessages every assistant text message in the turn, in order
 */
public record HarnessProcessResult(int exitCode, String standardOutput, String standardError, boolean completed,
	String assistantResponse, List<String> assistantMessages)
{
	/**
	 * Creates a result whose success requires both a zero exit code and no terminal diagnostic.
	 *
	 * @param exitCode the harness process exit code
	 *
	 * @param standardOutput captured standard output
	 *
	 * @param standardError captured standard error or terminal diagnostic
	 */
	public HarnessProcessResult(int exitCode, String standardOutput, String standardError)
	{
		this(exitCode, standardOutput, standardError, exitCode == 0 && standardError.isBlank(), "", List.of());
	}

	/**
	 * Creates a result with an explicit terminal state and no extracted assistant response.
	 *
	 * @param exitCode the harness process exit code
	 *
	 * @param standardOutput captured standard output
	 *
	 * @param standardError captured standard error or terminal diagnostic
	 *
	 * @param completed whether the harness emitted the terminal event for the requested turn
	 */
	public HarnessProcessResult(int exitCode, String standardOutput, String standardError, boolean completed)
	{
		this(exitCode, standardOutput, standardError, completed, "", List.of());
	}

	/**
	 * Validates captured process output and terminal state.
	 *
	 * @param exitCode the harness process exit code
	 *
	 * @param standardOutput captured standard output
	 *
	 * @param standardError captured standard error
	 *
	 * @param completed whether the harness emitted the terminal event for the requested turn
	 *
	 * @param assistantResponse the final assistant response
	 *
	 * @param assistantMessages every assistant text message in order
	 *
	 * @throws NullPointerException if {@code standardOutput}, {@code standardError}, {@code assistantResponse}
	 *   or {@code assistantMessages} are null, or {@code assistantMessages} contains null
	 */
	public HarnessProcessResult
	{
		requireThat(standardOutput, "standardOutput").isNotNull();
		requireThat(standardError, "standardError").isNotNull();
		requireThat(assistantResponse, "assistantResponse").isNotNull();
		requireThat(assistantMessages, "assistantMessages").isNotNull();
		assistantMessages = List.copyOf(assistantMessages);
	}
}
