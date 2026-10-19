package com.holdmyspot.cat.common.optimizer.contract;

import com.holdmyspot.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * An obligation that an agent receiving the prompt files must meet, its source, and the ways in which a reply can
 * fail it.
 *
 * @param id the identifier that is unique within the contract, in lowercase words joined by underscores
 * @param description what the prompt system must do
 * @param source the file or link that states the obligation, or {@value #GOAL} if the user's goal for the campaign
 *   states it
 * @param trigger the condition under which the obligation applies, or {@code null} if it always applies
 * @param behaviors the failure behaviors that a reply can show when it fails the requirement, such as
 *   {@code no_tool_call}: each a lowercase identifier of words joined by underscores, none of them
 *   {@value #OTHER_BEHAVIOR}
 */
public record Requirement(String id, String description, String source, String trigger, List<String> behaviors)
{
	/**
	 * The behavior that a judge names when a reply fails a requirement in a way that the requirement does not list.
	 */
	public static final String OTHER_BEHAVIOR = "other";
	/**
	 * The source of a requirement that the user's goal for the campaign states, rather than a prompt file.
	 */
	public static final String GOAL = "goal";

	/**
	 * Creates a requirement.
	 *
	 * @param id the identifier that is unique within the contract, in lowercase words joined by underscores
	 * @param description what the prompt system must do
	 * @param source the file or link that states the obligation, or {@value #GOAL} if the user's goal for the campaign
	 *   states it
	 * @param trigger the condition under which the obligation applies, or {@code null} if it always applies
	 * @param behaviors the failure behaviors that a reply can show when it fails the requirement
	 * @throws NullPointerException if {@code id}, {@code description}, {@code source} or {@code behaviors} is null,
	 *   or {@code behaviors} contains null
	 * @throws IllegalArgumentException if {@code id} or a behavior is not lowercase words joined by underscores,
	 *   {@code description}, {@code source} or a non-null {@code trigger} is blank, {@code behaviors} is empty or
	 *   lists a behavior twice, or a behavior is {@value #OTHER_BEHAVIOR}
	 */
	public Requirement
	{
		requireThat(id, "id").matches(Identifier.SNAKE_CASE);
		requireThat(description, "description").isNotBlank();
		requireThat(source, "source").isNotBlank();
		if (trigger != null)
			requireThat(trigger, "trigger").isNotBlank();
		requireThat(behaviors, "behaviors").isNotEmpty().doesNotContainDuplicates();
		for (String behavior : behaviors)
		{
			requireThat(behavior, "behavior").isNotNull();
			if (!Identifier.SNAKE_CASE.matcher(behavior).matches())
			{
				throw new IllegalArgumentException("The behavior '" + behavior + "' of the requirement '" + id +
					"' is not lowercase words joined by underscores");
			}
			if (behavior.equals(OTHER_BEHAVIOR))
			{
				throw new IllegalArgumentException("The requirement '" + id + "' lists the behavior '" +
					OTHER_BEHAVIOR + "', which a judge names for a failure that no listed behavior describes");
			}
		}
		behaviors = List.copyOf(behaviors);
	}

	/**
	 * Indicates whether a judge may name a behavior for a failure of this requirement.
	 *
	 * @param behavior a behavior
	 * @return {@code true} if the requirement lists the behavior or it is {@value #OTHER_BEHAVIOR}
	 * @throws NullPointerException if {@code behavior} is null
	 */
	public boolean admits(String behavior)
	{
		requireThat(behavior, "behavior").isNotNull();
		return behavior.equals(OTHER_BEHAVIOR) || behaviors.contains(behavior);
	}
}
