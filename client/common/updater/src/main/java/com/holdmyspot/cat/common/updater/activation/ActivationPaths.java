package com.holdmyspot.cat.common.updater.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * Validates the paths of {@link ActivationStep}s.
 */
final class ActivationPaths
{
	/**
	 * Refuses a path that is not absolute and normalized.
	 *
	 * @param path the path
	 * @param name the path's name in the failure message
	 * @throws NullPointerException     if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is relative, contains {@code .} or {@code ..} names, or is a
	 *                                  root
	 */
	static void requireAbsoluteNormalized(Path path, String name)
	{
		requireThat(path, name).isAbsolute();
		requireThat(path, name).isEqualTo(path.normalize(), name + ".normalize()");
		requireThat(path.getNameCount(), name + ".getNameCount()").isPositive();
	}

	/**
	 * Prevents instantiation.
	 */
	private ActivationPaths()
	{
	}
}
