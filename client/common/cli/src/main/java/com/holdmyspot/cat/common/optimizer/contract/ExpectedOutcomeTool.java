package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The built-in tool that a prompt test gives the assertion {@value TestCase#EXPECTED_OUTCOME}, which CAT adds to every
 * test and the judge decides unless the test gives it a tool. CAT still supplies the assertion's description and
 * kind.
 *
 * @param tool          the built-in tool and its arguments, which may name the test's variables and
 *                      {@value TestCase#EXPECTED_OUTCOME}
 * @param onToolFailure what a failure of {@code tool} means
 */
public record ExpectedOutcomeTool(String tool, OnToolFailure onToolFailure)
{
	/**
	 * Creates the tool of the assertion {@value TestCase#EXPECTED_OUTCOME}.
	 *
	 * @param tool          the built-in tool and its arguments
	 * @param onToolFailure what a failure of {@code tool} means
	 * @throws NullPointerException     if {@code tool} or {@code onToolFailure} are null
	 * @throws IllegalArgumentException if {@code tool} does not name a built-in tool with arguments that suit it
	 */
	public ExpectedOutcomeTool
	{
		requireThat(tool, "tool").isNotNull();
		requireThat(onToolFailure, "onToolFailure").isNotNull();
		BuiltInTool.parse(tool);
	}

	/**
	 * Creates the tool of the assertion {@value TestCase#EXPECTED_OUTCOME}, whose failure fails the trial.
	 *
	 * @param tool the built-in tool and its arguments
	 * @throws NullPointerException     if {@code tool} is null
	 * @throws IllegalArgumentException if {@code tool} does not name a built-in tool with arguments that suit it
	 */
	public ExpectedOutcomeTool(String tool)
	{
		this(tool, OnToolFailure.FAIL);
	}
}
