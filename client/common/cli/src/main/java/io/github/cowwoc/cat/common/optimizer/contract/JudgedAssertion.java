package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An assertion that the judge decides, with the requirement that it checks and the behaviors through which a trial
 * can fail it.
 *
 * @param assertion   the assertion, which has no {@code tool} or leaves a failure of its tool to the judge
 * @param requirement the requirement that the assertion checks, or {@code null} for
 *                    {@value TestCase#EXPECTED_OUTCOME}, which CAT adds
 */
public record JudgedAssertion(Assertion assertion, Requirement requirement)
{
	/**
	 * Creates a judged assertion.
	 *
	 * @param assertion   the assertion, which has no {@code tool} or leaves a failure of its tool to the judge
	 * @param requirement the requirement that the assertion checks, or {@code null} for
	 *                    {@value TestCase#EXPECTED_OUTCOME}, which CAT adds
	 * @throws NullPointerException     if {@code assertion} is null
	 * @throws IllegalArgumentException if a failure of {@code assertion}'s {@code tool} fails the trial, or
	 *                                  {@code requirement} is null for any assertion other than
	 *                                  {@value TestCase#EXPECTED_OUTCOME}
	 */
	public JudgedAssertion
	{
		requireThat(assertion, "assertion").isNotNull();
		if (assertion.tool() != null && assertion.onToolFailure() == OnToolFailure.FAIL)
		{
			throw new IllegalArgumentException("The assertion '" + assertion.id() + "' has a tool whose failure fails " +
				"the trial, so no judge decides it");
		}
		if (requirement == null && !assertion.id().equals(TestCase.EXPECTED_OUTCOME))
		{
			throw new IllegalArgumentException("The assertion '" + assertion.id() + "' checks no requirement, but only " +
				"the assertion '" + TestCase.EXPECTED_OUTCOME + "' may");
		}
	}

	/**
	 * Returns the assertions of a test that the judge decides in one trial.
	 *
	 * @param test         the prompt test
	 * @param requirements every requirement of the contract
	 * @param results      the results of the trial's built-in tools
	 * @return the test's assertions without a {@code tool} or whose result {@link BuiltInToolResult#leftToJudge()
	 *   leaves them to the judge}, in the test's order, then {@value TestCase#EXPECTED_OUTCOME}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an assertion checks a requirement that {@code requirements} does not list
	 */
	public static List<JudgedAssertion> of(PromptTest test, List<Requirement> requirements,
		List<BuiltInToolResult> results)
	{
		requireThat(test, "test").isNotNull();
		requireThat(requirements, "requirements").isNotNull();
		requireThat(results, "results").isNotNull();
		Map<String, Requirement> requirementById = new HashMap<>();
		for (Requirement requirement : requirements)
			requirementById.put(requirement.id(), requirement);
		Set<String> leftToJudge = new HashSet<>();
		for (BuiltInToolResult result : results)
		{
			if (result.leftToJudge())
				leftToJudge.add(result.assertion().id());
		}
		List<JudgedAssertion> judged = new ArrayList<>();
		for (MappedAssertion mapped : test.assertions())
		{
			if (mapped.assertion().tool() != null && !leftToJudge.contains(mapped.assertion().id()))
				continue;
			Requirement requirement = requirementById.get(mapped.requirementId());
			if (requirement == null)
			{
				throw new IllegalArgumentException("The assertion '" + mapped.assertion().id() + "' checks the " +
					"requirement '" + mapped.requirementId() + "', which is not listed");
			}
			judged.add(new JudgedAssertion(mapped.assertion(), requirement));
		}
		judged.add(new JudgedAssertion(test.expectedOutcome(), null));
		return judged;
	}

	/**
	 * Returns the behaviors through which a trial can fail the assertion, besides
	 * {@value Requirement#OTHER_BEHAVIOR}.
	 *
	 * @return the behaviors of the requirement, or {@value PromptTest#UNEXPECTED_OUTCOME} for
	 *   {@value TestCase#EXPECTED_OUTCOME}
	 */
	public List<String> behaviors()
	{
		if (requirement == null)
			return List.of(PromptTest.UNEXPECTED_OUTCOME);
		return requirement.behaviors();
	}

	/**
	 * Indicates whether a failure of the assertion may have a behavior.
	 *
	 * @param behavior the behavior
	 * @return {@code true} if the behavior is one of {@link #behaviors()} or {@value Requirement#OTHER_BEHAVIOR}
	 * @throws NullPointerException if {@code behavior} is null
	 */
	public boolean admits(String behavior)
	{
		requireThat(behavior, "behavior").isNotNull();
		return behavior.equals(Requirement.OTHER_BEHAVIOR) || behaviors().contains(behavior);
	}
}
