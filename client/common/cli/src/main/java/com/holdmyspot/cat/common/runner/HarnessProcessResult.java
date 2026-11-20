package com.holdmyspot.cat.common.runner;

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
 * @param completed indicates whether the requested turn completed successfully
 * @param terminal indicates whether the harness reported a terminal outcome for the requested turn
 *
 * @param assistantResponse the harness-specific adapter's extracted final assistant response
 *
 * @param assistantMessages every assistant text message in the turn, in order
 */
public record HarnessProcessResult(int exitCode, String standardOutput, String standardError, boolean completed,
	boolean terminal, String assistantResponse, List<String> assistantMessages)
{
	/**
	 * Creates a result using success as its terminal state.
	 *
	 * @param exitCode harness process exit code
	 * @param standardOutput captured standard output
	 * @param standardError captured standard error
	 * @param completed whether the turn completed successfully
	 * @param assistantResponse final assistant response
	 * @param assistantMessages assistant text messages in order
	 */
	public HarnessProcessResult(int exitCode, String standardOutput, String standardError, boolean completed,
		String assistantResponse, List<String> assistantMessages)
	{
		this(exitCode, standardOutput, standardError, completed, completed, assistantResponse, assistantMessages);
	}

	/**
	 * Creates a result without an extracted assistant response.
	 *
	 * @param exitCode harness process exit code
	 * @param standardOutput captured standard output
	 * @param standardError captured standard error
	 * @param completed whether the turn completed successfully
	 */
	public HarnessProcessResult(int exitCode, String standardOutput, String standardError, boolean completed)
	{
		this(exitCode, standardOutput, standardError, completed, completed, "", List.of());
	}

	/**
	 * Creates a result with separate success and terminal states.
	 *
	 * @param exitCode harness process exit code
	 * @param standardOutput captured standard output
	 * @param standardError captured standard error
	 * @param completed whether the turn completed successfully
	 * @param terminal whether the harness reported a terminal outcome
	 */
	public HarnessProcessResult(int exitCode, String standardOutput, String standardError, boolean completed,
		boolean terminal)
	{
		this(exitCode, standardOutput, standardError, completed, terminal, "", List.of());
	}

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
		this(exitCode, standardOutput, standardError, exitCode == 0 && standardError.isBlank(),
			exitCode == 0 && standardError.isBlank(), "", List.of());
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
		if (completed && !terminal)
			throw new IllegalArgumentException("A completed turn must be terminal");
		assistantMessages = List.copyOf(assistantMessages);
	}
}
