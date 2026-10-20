package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A reference in a prompt file that loads in trials to a file that trials cannot receive, such as an import of a
 * missing file. {@code start} refuses a campaign while its prompt system holds one, so that its trials never run
 * without a file that the prompt system names.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param file      the non-blank path of the prompt file that holds the reference, relative to the project
 * @param reference the non-blank reference as the prompt file writes it, without escapes
 * @param problem   why trials cannot receive the referenced file
 */
public record UnreachableReference(String file, String reference, Problem problem)
{
	/**
	 * Creates an unreachable reference.
	 *
	 * @param file      the non-blank path of the prompt file that holds the reference, relative to the project
	 * @param reference the non-blank reference as the prompt file writes it, without escapes
	 * @param problem   why trials cannot receive the referenced file
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code file} or {@code reference} are blank
	 */
	public UnreachableReference
	{
		requireThat(file, "file").isNotBlank();
		requireThat(reference, "reference").isNotBlank();
		requireThat(problem, "problem").isNotNull();
	}

	/**
	 * Returns a sentence that names the reference, the file that holds it and why trials cannot receive it.
	 *
	 * @return the sentence
	 */
	public String describe()
	{
		return switch (problem)
		{
			case MISSING -> file + " refers to " + reference + ", which does not exist.";
			case OUTSIDE_PROJECT -> file + " refers to " + reference + ", which lies outside the project.";
		};
	}

	/**
	 * Why trials cannot receive a referenced file.
	 */
	public enum Problem
	{
		/**
		 * The file does not exist.
		 */
		MISSING,
		/**
		 * The file lies outside the project, which is all that trials receive besides their prompt test's workspace
		 * files.
		 */
		OUTSIDE_PROJECT
	}
}
