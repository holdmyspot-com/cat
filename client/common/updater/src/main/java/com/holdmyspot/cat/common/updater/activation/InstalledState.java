package com.holdmyspot.cat.common.updater.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Optional;

/**
 * What a {@link PluginActivation} provider found installed in a harness's home directory.
 *
 * @param version    the version of the CAT plugin that the harness loads, or empty when the harness has no CAT
 *                   installation
 * @param consistent {@code true} if every copy of CAT that the provider maintains states the same version; when
 *                   {@code false}, an update reactivates the release even when its version equals {@code version}
 */
public record InstalledState(Optional<String> version, boolean consistent)
{
	/**
	 * Creates a state.
	 *
	 * @param version    the version of the CAT plugin that the harness loads, or empty when the harness has no CAT
	 *                   installation
	 * @param consistent {@code true} if every copy of CAT that the provider maintains states the same version
	 * @throws NullPointerException     if {@code version} is null
	 * @throws IllegalArgumentException if {@code version} holds blank text
	 */
	public InstalledState
	{
		requireThat(version, "version").isNotNull();
		if (version.isPresent())
			requireThat(version.get(), "version").isNotBlank();
	}
}
