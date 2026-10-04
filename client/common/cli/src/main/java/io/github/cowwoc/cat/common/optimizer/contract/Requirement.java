package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * An obligation that an agent receiving the prompt files must meet, and its source.
 *
 * @param id the identifier that is unique within the contract
 * @param description what the prompt system must do
 * @param source the file or link that states the obligation
 * @param trigger the condition under which the obligation applies, or {@code null} if it always applies
 */
public record Requirement(String id, String description, String source, String trigger)
{
	/**
	 * The requirement, present in every contract and checked by every test, that each reply correctly and completely
	 * does what the turn asked. CAT adds it, so its source is CAT. It always applies.
	 */
	public static final Requirement CORE_TASK = new Requirement("core-task",
		"Each reply correctly and completely does what the turn asked.", "CAT", null);

	/**
	 * Creates a requirement.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param description what the prompt system must do
	 * @param source the file or link that states the obligation
	 * @param trigger the condition under which the obligation applies, or {@code null} if it always applies
	 * @throws NullPointerException if {@code id}, {@code description} or {@code source} is null
	 * @throws IllegalArgumentException if {@code id}, {@code description}, {@code source} or a non-null
	 *   {@code trigger} is blank
	 */
	public Requirement
	{
		requireThat(id, "id").isNotBlank();
		requireThat(description, "description").isNotBlank();
		requireThat(source, "source").isNotBlank();
		if (trigger != null)
			requireThat(trigger, "trigger").isNotBlank();
	}
}
