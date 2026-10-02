package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * An obligation that an agent receiving the prompt files must meet, and its source.
 *
 * @param id the identifier that is unique within the contract
 * @param description what the prompt system must do
 * @param source the file or link that states the obligation
 */
public record Requirement(String id, String description, String source)
{
	/**
	 * The requirement, present in every contract and checked by every test, that each reply correctly and completely
	 * does what the turn asked. CAT adds it, so its source is CAT.
	 */
	public static final Requirement CORE_TASK = new Requirement("core-task",
		"Each reply correctly and completely does what the turn asked.", "CAT");

	/**
	 * Creates a requirement.
	 *
	 * @param id the identifier that is unique within the contract
	 * @param description what the prompt system must do
	 * @param source the file or link that states the obligation
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if any argument is blank
	 */
	public Requirement
	{
		requireThat(id, "id").isNotBlank();
		requireThat(description, "description").isNotBlank();
		requireThat(source, "source").isNotBlank();
	}
}
