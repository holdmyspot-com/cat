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
 * @param judgeReason   why no built-in tool decides it, or {@code null} for a legacy assertion or one with a tool
 */
public record Assertion(String id, AssertionKind kind, String tool, OnToolFailure onToolFailure, String statement,
	String judgeReason)
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
		if (tool != null && onToolFailure != OnToolFailure.JUDGE && judgeReason != null)
			throw new IllegalArgumentException("The assertion '" + id + "' has a tool and needs no judge reason");
	}

	/**
	 * Creates an assertion without a judge reason.
	 *
	 * @param id the assertion's identifier
	 * @param kind whether it requires an effect or guards against a harm
	 * @param tool the built-in tool, or {@code null} when the judge decides it
	 * @param onToolFailure what a tool failure means
	 * @param statement what the assertion requires
	 */
	public Assertion(String id, AssertionKind kind, String tool, OnToolFailure onToolFailure, String statement)
	{
		this(id, kind, tool, onToolFailure, statement, null);
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
		return expectedOutcome(kind, Optional.empty());
	}

	/**
	 * Returns the assertion that CAT adds to every prompt test: that the reply's result matches the test case's
	 * expected outcome. The judge decides it unless the test gives it a built-in tool.
	 *
	 * @param kind {@link AssertionKind#GUARD} when every other assertion of the test is a guard, otherwise
	 *             {@link AssertionKind#EFFECT}
	 * @param tool the built-in tool that the test gives the assertion, or empty if the judge decides it
	 * @return the assertion
	 * @throws NullPointerException if any argument is null
	 */
	public static Assertion expectedOutcome(AssertionKind kind, Optional<ExpectedOutcomeTool> tool)
	{
		return expectedOutcome(kind, tool, null);
	}

	/**
	 * Returns the added assertion, including why the judge decides it when the contract author supplied a reason.
	 *
	 * @param kind whether it is an effect or guard
	 * @param tool its built-in tool, if any
	 * @param judgeReason why no built-in tool decides it, or {@code null} for a legacy contract
	 * @return the expected outcome assertion
	 */
	public static Assertion expectedOutcome(AssertionKind kind, Optional<ExpectedOutcomeTool> tool, String judgeReason)
	{
		requireThat(tool, "tool").isNotNull();
		if (tool.isEmpty())
			return new Assertion(TestCase.EXPECTED_OUTCOME, kind, null, OnToolFailure.FAIL,
				EXPECTED_OUTCOME_STATEMENT, judgeReason);
		return new Assertion(TestCase.EXPECTED_OUTCOME, kind, tool.get().tool(), tool.get().onToolFailure(),
			EXPECTED_OUTCOME_STATEMENT, judgeReason);
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
