package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * An obligation that the prompt system must meet, and where it was found.
 *
 * @param id the identifier that is unique within the contract
 * @param description what the prompt system must do
 * @param source the file or link that states the obligation
 */
public record Requirement(String id, String description, String source)
{
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
