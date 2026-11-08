package com.holdmyspot.cat.common.installer.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The question that install puts to the user before it makes a change that also changes a setting that is not CAT's
 * alone.
 *
 * @param question the question, which explains the problem, what fails or stays unprotected without the change, and
 *                 the change
 * @param required {@code true} if install stops and changes nothing when the user withholds permission, because CAT
 *                 cannot work without the change; {@code false} if install goes on without the change, and
 *                 {@link HarnessIntegration#describeAdded} tells the user what stays unprotected
 */
public record ConsentRequest(String question, boolean required)
{
	/**
	 * Creates a request.
	 *
	 * @param question the question
	 * @param required {@code true} if install stops when the user withholds permission
	 * @throws NullPointerException     if {@code question} is null
	 * @throws IllegalArgumentException if {@code question} is blank
	 */
	public ConsentRequest
	{
		requireThat(question, "question").isNotBlank();
	}
}
