package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * An assertion that the judge decides, with the requirement that it checks and the behaviors through which a trial
 * can fail it.
 *
 * @param assertion   the assertion, which has no {@code tool} or leaves a failure of its tool to the judge
 * @param requirement the requirement that the assertion checks
 */
public record JudgedAssertion(Assertion assertion, Requirement requirement)
{
	/**
	 * Creates a judged assertion.
	 *
	 * @param assertion   the assertion, which has no {@code tool} or leaves a failure of its tool to the judge
	 * @param requirement the requirement that the assertion checks
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if a failure of {@code assertion}'s {@code tool} fails the trial
	 */
	public JudgedAssertion
	{
		requireThat(assertion, "assertion").isNotNull();
		requireThat(requirement, "requirement").isNotNull();
		if (assertion.tool() != null && assertion.onToolFailure() == OnToolFailure.FAIL)
		{
			throw new IllegalArgumentException("The assertion '" + assertion.id() + "' has a tool whose failure fails " +
				"the trial, so no judge decides it");
		}
	}

	/**
	 * Returns the assertions of a test that the judge decides in one trial.
	 *
	 * @param test         the prompt test
	 * @param requirements every requirement of the contract
	 * @param results      the results of the trial's built-in tools
	 * @return the test's assertions without a {@code tool} or whose result {@link BuiltInToolResult#leftToJudge()
	 *   leaves them to the judge}, in the test's order, or empty if built-in tools decided every assertion
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an assertion checks a requirement that {@code requirements} does not list
	 */
	public static List<JudgedAssertion> of(PromptTest test, List<Requirement> requirements,
		List<BuiltInToolResult> results)
	{
		requireThat(test, "test").isNotNull();
		requireThat(requirements, "requirements").isNotNull();
		requireThat(results, "results").isNotNull();
		Set<String> leftToJudge = new HashSet<>();
		for (BuiltInToolResult result : results)
		{
			if (result.leftToJudge())
				leftToJudge.add(result.assertion().id());
		}
		return select(test, requirements, assertion -> assertion.tool() == null || leftToJudge.contains(assertion.id()));
	}

	/**
	 * Returns every assertion of a test that the judge may decide, whatever a trial's built-in tools found: those
	 * without a {@code tool}, and those whose {@code tool} leaves its failure to the judge. A reply that is judged
	 * without a trial, such as a near miss, is judged against these.
	 *
	 * @param test         the prompt test
	 * @param requirements every requirement of the contract
	 * @return the assertions, in the order of {@link #of}; empty if every assertion has a tool whose failure fails the
	 *   trial
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if an assertion checks a requirement that {@code requirements} does not list
	 */
	public static List<JudgedAssertion> ofJudgeable(PromptTest test, List<Requirement> requirements)
	{
		requireThat(test, "test").isNotNull();
		requireThat(requirements, "requirements").isNotNull();
		return select(test, requirements,
			assertion -> assertion.tool() == null || assertion.onToolFailure() == OnToolFailure.JUDGE);
	}

	/**
	 * Returns the assertions of a test that a filter selects.
	 *
	 * @param test         the prompt test
	 * @param requirements every requirement of the contract
	 * @param decided      returns {@code true} for an assertion that the judge decides
	 * @return the selected assertions in the test's order
	 * @throws IllegalArgumentException if a selected assertion checks a requirement that {@code requirements} does not
	 *                                  list
	 */
	private static List<JudgedAssertion> select(PromptTest test, List<Requirement> requirements,
		Predicate<Assertion> decided)
	{
		Map<String, Requirement> requirementById = new HashMap<>();
		for (Requirement requirement : requirements)
			requirementById.put(requirement.id(), requirement);
		List<JudgedAssertion> judged = new ArrayList<>();
		for (MappedAssertion mapped : test.assertions())
		{
			if (!decided.test(mapped.assertion()))
				continue;
			Requirement requirement = requirementById.get(mapped.requirementId());
			if (requirement == null)
			{
				throw new IllegalArgumentException("The assertion '" + mapped.assertion().id() + "' checks the " +
					"requirement '" + mapped.requirementId() + "', which is not listed");
			}
			judged.add(new JudgedAssertion(mapped.assertion(), requirement));
		}
		return judged;
	}

	/**
	 * Returns what the assertion's judge reads.
	 *
	 * @return the assertion's declared scope, or {@link EvidenceScope#ACTIONS} if it declares none: an assertion of a
	 *   contract that predates the declaration
	 */
	public EvidenceScope scope()
	{
		if (assertion.evidenceScope() == null)
			return EvidenceScope.ACTIONS;
		return assertion.evidenceScope();
	}

	/**
	 * Returns the behaviors through which a trial can fail the assertion, besides
	 * {@value Requirement#OTHER_BEHAVIOR}.
	 *
	 * @return the behaviors of the requirement
	 */
	public List<String> behaviors()
	{
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
