package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A prompt file that selects a model, a reasoning effort or both. These selections bind the profile values used to
 * derive the file's trial profiles.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param file the source name used by the requirements that the file supplies
 * @param profile the selected model and effort; an empty value leaves that choice open
 */
public record ProfileBinding(String file, TrialProfile profile)
{
	/**
	 * Creates a binding.
	 *
	 * @param file the source name used by the requirements that the file supplies
	 * @param profile the selected model and effort; an empty value leaves that choice open
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code file} is blank or {@code profile} selects neither a model nor an
	 *   effort
	 */
	public ProfileBinding
	{
		requireThat(file, "file").isNotBlank();
		requireThat(profile, "profile").isNotNull();
		requireThat(profile.isBound(), "profile.isBound()").isTrue();
	}
}
