package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A prompt file that binds a model, a reasoning effort or both (design &sect;6.6).
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param file the file, as the requirements that it supplies name their source
 * @param profile the bound dimensions; an empty dimension is left open
 */
public record ProfileBinding(String file, TrialProfile profile)
{
	/**
	 * Creates a binding.
	 *
	 * @param file the file, as the requirements that it supplies name their source
	 * @param profile the bound dimensions; an empty dimension is left open
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code file} is blank or {@code profile} binds nothing
	 */
	public ProfileBinding
	{
		requireThat(file, "file").isNotBlank();
		requireThat(profile, "profile").isNotNull();
		requireThat(profile.isBound(), "profile.isBound()").isTrue();
	}
}
