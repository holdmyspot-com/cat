package com.holdmyspot.cat.common.optimizer.campaign;

import com.holdmyspot.cat.common.Identifier;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * An assertion that a trial failed, and the behavior through which it failed.
 * <p>
 * A failed built-in tool contributes the behavior that the tool detects. A judge contributes one of the behaviors
 * that the assertion admits, or {@code other} when none of them describes the failure.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param assertionId the identifier of the failed assertion
 * @param behavior    the behavior through which the assertion failed
 */
public record FailedAssertion(String assertionId, String behavior)
{
	/**
	 * Creates a failed assertion.
	 *
	 * @param assertionId the identifier of the failed assertion
	 * @param behavior    the behavior through which the assertion failed
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code assertionId} is not lowercase words joined by underscores or
	 *                                  {@code behavior} is blank
	 */
	public FailedAssertion
	{
		requireThat(assertionId, "assertionId").matches(Identifier.SNAKE_CASE);
		requireThat(behavior, "behavior").isNotBlank();
	}
}
