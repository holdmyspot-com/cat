package com.holdmyspot.cat.common.optimizer.contract;

import com.holdmyspot.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Map;
import java.util.Optional;

/**
 * One assertion of a scenario: a statement that a compliant trial satisfies.
 *
 * @param id            the identifier that is unique within the scenario
 * @param kind          whether the assertion requires the prompt's effect or guards against a harm
 * @param tool          the built-in tool and its arguments that decide the assertion, or {@code null} if the judge
 *                      decides it
 * @param onToolFailure what a failure of {@code tool} means; {@link OnToolFailure#FAIL} when {@code tool} is
 *                      {@code null}
 * @param statement     what the assertion requires
 */
public record Assertion(String id, AssertionKind kind, String tool, OnToolFailure onToolFailure, String statement)
{
	/**
	 * What the assertion that CAT adds to every prompt test requires.
	 */
	public static final String EXPECTED_OUTCOME_STATEMENT =
		"The reply's result matches this test case's expected outcome.";

	/**
	 * Creates an assertion.
	 *
	 * @param id            the identifier that is unique within the scenario
	 * @param kind          whether the assertion requires the prompt's effect or guards against a harm
	 * @param tool          the built-in tool and its arguments that decide the assertion, or {@code null} if the
	 *                      judge decides it
	 * @param onToolFailure what a failure of {@code tool} means; {@link OnToolFailure#FAIL} when {@code tool} is
	 *                      {@code null}
	 * @param statement     what the assertion requires
	 * @throws NullPointerException     if {@code id}, {@code kind}, {@code onToolFailure} or {@code statement} are
	 *                                  null
	 * @throws IllegalArgumentException if {@code id} is not lowercase words joined by underscores, {@code tool} does
	 *                                  not name a built-in tool with arguments that suit it, {@code onToolFailure} is
	 *                                  {@link OnToolFailure#JUDGE} without a {@code tool}, or {@code statement} is
	 *                                  blank
	 */
	public Assertion
	{
		requireThat(id, "id").matches(Identifier.SNAKE_CASE);
		requireThat(kind, "kind").isNotNull();
		requireThat(onToolFailure, "onToolFailure").isNotNull();
		if (tool != null)
			BuiltInTool.parse(tool);
		else if (onToolFailure == OnToolFailure.JUDGE)
		{
			throw new IllegalArgumentException("The assertion '" + id + "' leaves a tool failure to the judge but has " +
				"no tool");
		}
		requireThat(statement, "statement").isNotBlank();
	}

	/**
	 * Creates an assertion whose built-in tool, if any, fails the trial when it fails.
	 *
	 * @param id        the identifier that is unique within the scenario
	 * @param kind      whether the assertion requires the prompt's effect or guards against a harm
	 * @param tool      the built-in tool and its arguments that decide the assertion, or {@code null} if the judge
	 *                  decides it
	 * @param statement what the assertion requires
	 * @throws NullPointerException     if {@code id}, {@code kind} or {@code statement} are null
	 * @throws IllegalArgumentException if {@code id} is not lowercase words joined by underscores, {@code tool} does
	 *                                  not name a built-in tool with arguments that suit it, or {@code statement} is
	 *                                  blank
	 */
	public Assertion(String id, AssertionKind kind, String tool, String statement)
	{
		this(id, kind, tool, OnToolFailure.FAIL, statement);
	}

	/**
	 * Returns the assertion that CAT adds to every prompt test: that the reply's result matches the test case's
	 * expected outcome. The judge decides it.
	 *
	 * @param kind {@link AssertionKind#GUARD} when every other assertion of the test is a guard, otherwise
	 *             {@link AssertionKind#EFFECT}
	 * @return the assertion
	 * @throws NullPointerException if {@code kind} is null
	 */
	public static Assertion expectedOutcome(AssertionKind kind)
	{
		return new Assertion(TestCase.EXPECTED_OUTCOME, kind, null, EXPECTED_OUTCOME_STATEMENT);
	}

	/**
	 * Returns the built-in tool that decides the assertion.
	 *
	 * @return the tool, or empty if the judge decides the assertion
	 */
	public Optional<BuiltInTool> builtInTool()
	{
		if (tool == null)
			return Optional.empty();
		return Optional.of(BuiltInTool.parse(tool));
	}

	/**
	 * Returns the statement with each placeholder replaced by a test case's text.
	 *
	 * @param textByName the text of each placeholder, by name
	 * @return the statement for that test case
	 * @throws NullPointerException if {@code textByName} is null
	 */
	public String statement(Map<String, String> textByName)
	{
		requireThat(textByName, "textByName").isNotNull();
		return Placeholders.fillScenario(statement, textByName);
	}
}
