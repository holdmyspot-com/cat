package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * An assertion of a prompt test, together with the requirement whose compliance it shows.
 *
 * @param assertion     the assertion
 * @param requirementId the identifier of the requirement that the assertion checks
 */
public record MappedAssertion(Assertion assertion, String requirementId)
{
	/**
	 * Creates a mapped assertion.
	 *
	 * @param assertion     the assertion
	 * @param requirementId the identifier of the requirement that the assertion checks
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code requirementId} is not lowercase words joined by underscores
	 */
	public MappedAssertion
	{
		requireThat(assertion, "assertion").isNotNull();
		requireThat(requirementId, "requirementId").matches(Identifier.SNAKE_CASE);
	}
}
