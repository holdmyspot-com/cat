package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Outcome;

import java.util.List;

/**
 * A condition that makes certification unavailable, as the contract author found it in the prompt system.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param outcome the matching {@code certification unavailable} outcome
 * @param requirementIds a non-empty list of the requirement identifiers affected by the condition
 * @param evidence non-blank text identifying the file and instructions that show the condition
 */
public record ScopeCondition(Outcome outcome, List<String> requirementIds, String evidence)
{
	/**
	 * Creates a condition.
	 *
	 * @param outcome the matching {@code certification unavailable} outcome
	 * @param requirementIds a non-empty list of the requirement identifiers affected by the condition
	 * @param evidence non-blank text identifying the file and instructions that show the condition
	 * @throws NullPointerException if any of the arguments are null, or {@code requirementIds} contains null
	 * @throws IllegalArgumentException if {@code outcome} does not report unavailability, {@code requirementIds} is
	 *   empty, or {@code evidence} is blank
	 */
	public ScopeCondition
	{
		requireThat(outcome, "outcome").isNotNull();
		requireThat(outcome.isUnavailable(), "outcome.isUnavailable()").isTrue();
		requireThat(requirementIds, "requirementIds").isNotEmpty();
		requireThat(evidence, "evidence").isNotBlank();
		requirementIds = List.copyOf(requirementIds);
	}
}
