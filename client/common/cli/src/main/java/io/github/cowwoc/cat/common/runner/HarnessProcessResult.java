package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Captures a harness CLI command's exit code, terminal-turn state, and redacted standard-output and standard-error
 * text.
 *
 * @param exitCode the native process exit code
 *
 * @param standardOutput captured standard output
 *
 * @param standardError captured standard error
 *
 * @param outcome the terminal state of the requested turn
 *
 * @param assistantResponse the harness-specific adapter's extracted final assistant response
 */
public record HarnessProcessResult(int exitCode, String standardOutput, String standardError, Outcome outcome,
	String assistantResponse)
{
	/**
	 * Distinguishes a completed turn, acknowledged interruption, and native failure.
	 */
	public enum Outcome
	{
		COMPLETED,
		INTERRUPTED,
		FAILED
	}

	/**
	 * Creates a result whose success requires both a zero exit code and no terminal diagnostic.
	 *
	 * @param exitCode the native process exit code
	 *
	 * @param standardOutput captured standard output
	 *
	 * @param standardError captured standard error or terminal diagnostic
	 */
	public HarnessProcessResult(int exitCode, String standardOutput, String standardError)
	{
		this(exitCode, standardOutput, standardError, outcome(exitCode == 0 && standardError.isBlank()), "");
	}

	/**
	 * Creates a result with an explicit terminal state and no extracted assistant response.
	 *
	 * @param exitCode the native process exit code
	 *
	 * @param standardOutput captured standard output
	 *
	 * @param standardError captured standard error or terminal diagnostic
	 *
	 * @param completed whether the harness emitted the terminal event for the requested turn
	 */
	public HarnessProcessResult(int exitCode, String standardOutput, String standardError, boolean completed)
	{
		this(exitCode, standardOutput, standardError, outcome(completed), "");
	}

	/**
	 * Creates a result with an explicit completion decision and assistant response.
	 *
	 * @param exitCode the native process exit code
	 * @param standardOutput captured standard output
	 * @param standardError captured standard error
	 * @param completed whether the native turn completed
	 * @param assistantResponse the extracted final assistant response
	 */
	public HarnessProcessResult(int exitCode, String standardOutput, String standardError, boolean completed,
		String assistantResponse)
	{
		this(exitCode, standardOutput, standardError, outcome(completed), assistantResponse);
	}

	/**
	 * Validates captured process output and terminal state.
	 *
	 * @param exitCode the native process exit code
	 *
	 * @param standardOutput captured standard output
	 *
	 * @param standardError captured standard error
	 *
	 * @throws NullPointerException if {@code standardOutput} or {@code standardError} are null
	 */
	public HarnessProcessResult
	{
		requireThat(standardOutput, "standardOutput").isNotNull();
		requireThat(standardError, "standardError").isNotNull();
		requireThat(outcome, "outcome").isNotNull();
		requireThat(assistantResponse, "assistantResponse").isNotNull();
	}

	/**
	 * Indicates whether the native turn reached its normal completed outcome.
	 *
	 * @return true when the native turn completed
	 */
	public boolean completed()
	{
		return outcome == Outcome.COMPLETED;
	}

	/**
	 * Converts the older boolean completion decision at its construction boundary.
	 *
	 * @param completed whether the native turn completed
	 * @return the corresponding outcome
	 */
	private static Outcome outcome(boolean completed)
	{
		if (completed)
			return Outcome.COMPLETED;
		return Outcome.FAILED;
	}
}
