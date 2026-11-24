package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The mechanical result for one proposal's assertion example.
 *
 * @param testId the prompt test identifier
 * @param assertionId the assertion identifier
 * @param exampleId the example identifier
 * @param tool the built-in tool's name, or null when the judge decides the assertion
 * @param passed whether the tool passed, or false when there is no tool
 */
public record AssertionExampleCheck(String testId, String assertionId, String exampleId, String tool, boolean passed)
{
	/**
	 * Creates the result.
	 *
	 * @param testId the prompt test identifier
	 * @param assertionId the assertion identifier
	 * @param exampleId the example identifier
	 * @param tool the built-in tool's name, or null when the judge decides the assertion
	 * @param passed whether the tool passed, or false when there is no tool
	 * @throws NullPointerException if a required argument is null
	 * @throws IllegalArgumentException if an identifier is blank or a missing tool passed
	 */
	public AssertionExampleCheck
	{
		requireThat(testId, "testId").isNotBlank();
		requireThat(assertionId, "assertionId").isNotBlank();
		requireThat(exampleId, "exampleId").isNotBlank();
		if (tool == null && passed)
			throw new IllegalArgumentException("An example without a tool cannot have a passing tool result");
	}
}
