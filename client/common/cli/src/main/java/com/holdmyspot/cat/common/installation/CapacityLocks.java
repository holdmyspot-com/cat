package com.holdmyspot.cat.common.installation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * Locates the lock files through which every CAT process of a user shares each harness's session capacity. They live
 * in the user's {@code .cat} directory, outside every harness installation, so that sessions started from either
 * harness's plugin count against the same limit.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CapacityLocks
{
	/**
	 * Prevents instantiation.
	 */
	private CapacityLocks()
	{
	}

	/**
	 * Returns the directory that holds every capacity lock. Sessions that start further sessions must be able to write
	 * it.
	 *
	 * @param userHome the user's home directory
	 * @return {@code .cat/locks} below {@code userHome}
	 * @throws NullPointerException if {@code userHome} is null
	 */
	public static Path root(Path userHome)
	{
		requireThat(userHome, "userHome").isNotNull();
		return userHome.resolve(".cat/locks");
	}

	/**
	 * Returns the directory that holds one harness's capacity slot files.
	 *
	 * @param userHome the user's home directory
	 * @param harness  the harness identifier
	 * @return {@code harness-capacity/<harness>} below {@link #root(Path)}
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public static Path harnessCapacity(Path userHome, String harness)
	{
		requireThat(harness, "harness").isNotBlank();
		return root(userHome).resolve("harness-capacity").resolve(harness);
	}
}
