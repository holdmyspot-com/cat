package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The result of the built-in tool that decides one assertion in one trial.
 *
 * @param assertion the assertion that the tool decides
 * @param outcome   how the tool finished
 * @param observed  what the tool found, quoting the evidence
 */
public record BuiltInToolResult(Assertion assertion, Outcome outcome, String observed)
{
	/**
	 * How the built-in tool finished.
	 */
	public enum Outcome
	{
		/**
		 * The evidence meets the tool's condition.
		 */
		PASS,
		/**
		 * The evidence fails the tool's condition.
		 */
		FAIL,
		/**
		 * The check program could not decide from the evidence.
		 */
		PROGRAM_ERROR
	}

	/**
	 * Creates a result.
	 *
	 * @param assertion the assertion that the tool decides
	 * @param outcome   how the tool finished
	 * @param observed  what the tool found, quoting the evidence
	 * @throws NullPointerException if an argument is null or an ordinary result has no tool
	 * @throws IllegalArgumentException if {@code observed} is blank or a program error has no check-program tool
	 */
	public BuiltInToolResult
	{
		requireThat(assertion, "assertion").isNotNull();
		requireThat(outcome, "outcome").isNotNull();
		if (outcome == Outcome.PROGRAM_ERROR &&
			!(assertion.builtInTool().orElse(null) instanceof BuiltInTool.CheckProgram))
		{
			throw new IllegalArgumentException("Program errors require a check-program assertion: " + assertion.id());
		}
		requireThat(assertion.tool(), "assertion.tool()").isNotNull();
		requireThat(observed, "observed").isNotBlank();
	}

	/**
	 * Creates an ordinary pass or failure.
	 *
	 * @param assertion the assertion that the tool decides
	 * @param passed    {@code true} if the trial passed the tool
	 * @param observed  what the tool found, quoting the evidence
	 * @throws NullPointerException if either reference argument is null or the assertion has no tool
	 * @throws IllegalArgumentException if {@code observed} is blank
	 */
	public BuiltInToolResult(Assertion assertion, boolean passed, String observed)
	{
		this(assertion, ordinaryOutcome(passed), observed);
	}

	/**
	 * Creates the result of a check program that could not decide because of a program error.
	 *
	 * @param assertion the assertion whose check program errored
	 * @param observed  the cause and retained output
	 * @return the program error result
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if the assertion has no check-program tool or {@code observed} is blank
	 */
	public static BuiltInToolResult programError(Assertion assertion, String observed)
	{
		return new BuiltInToolResult(assertion, Outcome.PROGRAM_ERROR, observed);
	}

	/**
	 * Returns the outcome represented by the ordinary result constructor.
	 *
	 * @param passed whether the trial passed the tool
	 * @return the outcome
	 */
	private static Outcome ordinaryOutcome(boolean passed)
	{
		if (passed)
			return Outcome.PASS;
		return Outcome.FAIL;
	}

	/**
	 * Indicates whether the trial passed the tool.
	 *
	 * @return {@code true} if the tool passed
	 */
	public boolean passed()
	{
		return outcome == Outcome.PASS;
	}

	/**
	 * Indicates whether the result fails the trial without a judge.
	 *
	 * @return {@code true} if the tool failed and its assertion's {@code on_tool_failure} is
	 *   {@link OnToolFailure#FAIL}
	 */
	public boolean failsTrial()
	{
		return !passed() && assertion.onToolFailure() == OnToolFailure.FAIL;
	}

	/**
	 * Indicates whether the result leaves its assertion to the judge.
	 *
	 * @return {@code true} if the tool failed and its assertion's {@code on_tool_failure} is
	 *   {@link OnToolFailure#JUDGE}
	 */
	public boolean leftToJudge()
	{
		return !passed() && assertion.onToolFailure() == OnToolFailure.JUDGE;
	}

	/**
	 * Returns the behavior that a failure of the tool shows.
	 *
	 * @return the behavior's name
	 */
	public String behavior()
	{
		if (outcome == Outcome.PROGRAM_ERROR)
			return "check_program_error";
		return assertion.builtInTool().orElseThrow().behavior();
	}

	/**
	 * Returns the result as one line: the assertion, its {@code tool}, the outcome and the observed value. A
	 * failure also names its behavior.
	 *
	 * @return the description
	 */
	public String describe()
	{
		String outcome;
		if (passed())
			outcome = "passed";
		else
			outcome = "failed (" + behavior() + ")";
		return "Assertion `" + assertion.id() + "`, `tool: " + assertion.tool() + "`: " + outcome + ". Observed: " +
			observed;
	}
}
