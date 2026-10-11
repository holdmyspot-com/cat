package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * One pair of a scenario's {@code ## Variables} section: a variable that every test case of the prompt test gives a
 * value.
 *
 * @param name        the name that {@code {{name}}} placeholders use
 * @param description what the variable's value represents
 */
public record VariableDeclaration(String name, String description)
{
	/**
	 * Creates a declaration.
	 *
	 * @param name        the name that {@code {{name}}} placeholders use
	 * @param description what the variable's value represents
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code name} is not lowercase words joined by underscores, is
	 *                                  {@value TestCase#EXPECTED_OUTCOME}, or {@code description} is blank
	 */
	public VariableDeclaration
	{
		requireThat(name, "name").matches(Identifier.SNAKE_CASE).isNotEqualTo(TestCase.EXPECTED_OUTCOME);
		requireThat(description, "description").isNotBlank();
	}
}
