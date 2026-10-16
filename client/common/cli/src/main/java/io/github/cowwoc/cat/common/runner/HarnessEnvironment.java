package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.Platform;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Builds the minimal environment inherited by isolated harness processes.
 */
public final class HarnessEnvironment
{
	/**
	 * Copies only generic operating-system variables and removes CAT/harness home state.
	 * <p>
	 * On Windows, the copy also holds {@code USERPROFILE}, {@code TEMP} and {@code TMP}. Windows programs read the
	 * user's home directory from {@code USERPROFILE}, and a program without {@code TEMP} and {@code TMP} puts its
	 * temporary files in the Windows directory, which a user cannot write.
	 *
	 * @param source the host environment
	 *
	 * @return the scrubbed environment
	 */
	public static Map<String, String> scrub(Map<String, String> source)
	{
		requireThat(source, "source").isNotNull();
		Map<String, String> result = new HashMap<>();
		copy(source, result, "PATH");
		copy(source, result, "HOME");
		copy(source, result, "LANG");
		copy(source, result, "LC_ALL");
		copy(source, result, "TERM");
		if (Platform.current().isWindows())
		{
			copy(source, result, "USERPROFILE");
			copy(source, result, "TEMP");
			copy(source, result, "TMP");
		}
		return Map.copyOf(result);
	}

	/**
	 * Copies explicitly approved authentication variables into an isolated environment.
	 *
	 * @param source the host environment
	 *
	 * @param destination the mutable isolated environment
	 *
	 * @param names the approved authentication variable names
	 */
	public static void copyAllowed(Map<String, String> source, Map<String, String> destination, Set<String> names)
	{
		requireThat(source, "source").isNotNull();
		requireThat(destination, "destination").isNotNull();
		requireThat(names, "names").isNotNull();
		for (String name : names)
		{
			requireThat(name, "name").isNotBlank();
			copy(source, destination, name);
		}
	}

	/**
	 * Stops Git commands in an owned project copy from discovering a repository in the host's parent directories.
	 *
	 * @param environment the mutable isolated environment
	 *
	 * @param runDirectory the owned directory immediately above the copied project
	 */
	public static void preventGitDiscoveryAbove(Map<String, String> environment, Path runDirectory)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(runDirectory, "runDirectory").isNotNull();
		if (!runDirectory.isAbsolute())
			throw new IllegalArgumentException("runDirectory must be absolute");
		environment.put("GIT_CEILING_DIRECTORIES", runDirectory.toString());
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessEnvironment()
	{
	}

	/**
	 * Copies a non-blank environment value.
	 *
	 * @param source the source environment
	 *
	 * @param destination the destination environment
	 *
	 * @param name the variable name
	 */
	private static void copy(Map<String, String> source, Map<String, String> destination, String name)
	{
		assert source != null;
		assert destination != null;
		assert name != null;
		String value = source.get(name);
		if (value != null && !value.isBlank())
			destination.put(name, value);
	}
}
