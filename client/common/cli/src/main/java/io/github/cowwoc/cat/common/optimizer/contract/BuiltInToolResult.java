package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The result of the built-in tool that decides one assertion in one trial.
 *
 * @param assertion the assertion that the tool decides
 * @param passed    {@code true} if the trial passed the tool
 * @param observed  what the tool found, quoting the evidence
 */
public record BuiltInToolResult(Assertion assertion, boolean passed, String observed)
{
	/**
	 * Creates a result.
	 *
	 * @param assertion the assertion that the tool decides
	 * @param passed    {@code true} if the trial passed the tool
	 * @param observed  what the tool found, quoting the evidence
	 * @throws NullPointerException     if {@code assertion} or {@code observed} are null
	 * @throws IllegalArgumentException if {@code assertion} has no {@code tool:} line or {@code observed} is blank
	 */
	public BuiltInToolResult
	{
		requireThat(assertion, "assertion").isNotNull();
		requireThat(assertion.tool(), "assertion.tool()").isNotNull();
		requireThat(observed, "observed").isNotBlank();
	}

	/**
	 * Returns the behavior that a failure of the tool shows.
	 *
	 * @return the behavior's name
	 */
	public String behavior()
	{
		return assertion.builtInTool().orElseThrow().behavior();
	}

	/**
	 * Returns the result as one line: the assertion, its {@code tool:} line, the outcome and the observed value. A
	 * failure also names its behavior.
	 *
	 * @return the description
	 */
	public String describe()
	{
		String outcome;
		if (passed)
			outcome = "passed";
		else
			outcome = "failed (" + behavior() + ")";
		return "Assertion `" + assertion.id() + "`, `tool: " + assertion.tool() + "`: " + outcome + ". Observed: " +
			observed;
	}
}
