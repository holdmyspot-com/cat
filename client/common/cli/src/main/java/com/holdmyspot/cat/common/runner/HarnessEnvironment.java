package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Platform;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Builds the minimal environment inherited by isolated harness processes.
 */
public final class HarnessEnvironment
{
	/**
	 * The upper-case names of the variables that route network connections through a proxy.
	 */
	private static final List<String> PROXY_VARIABLES = List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY");

	/**
	 * Copies only generic operating-system variables and removes CAT/harness home state.
	 * <p>
	 * The copy also holds {@code NVM_DIR}, the Node version manager's directory. A {@code node} launcher that loads the
	 * manager finds it through {@code NVM_DIR}, which defaults to {@code .nvm} in the home directory, and a child's
	 * home is the run's own, so without the variable the Node programs that the harness runs would not start.
	 * <p>
	 * The copy also holds the proxy variables, in upper and lower case: {@code HTTP_PROXY}, {@code HTTPS_PROXY},
	 * {@code ALL_PROXY} and {@code NO_PROXY}. A host that reaches the network only through a proxy names it there,
	 * and a harness that lacks them cannot reach its model service.
	 * <p>
	 * On Windows, the copy also holds {@code USERPROFILE}, {@code LOCALAPPDATA}, {@code TEMP}, {@code TMP},
	 * {@code PATHEXT} and {@code SystemDrive}. Windows programs read the user's home directory from
	 * {@code USERPROFILE} and its local data folder from {@code LOCALAPPDATA}; a program without {@code TEMP} and
	 * {@code TMP} puts its temporary files in the Windows directory, which a user cannot write; PowerShell finds a
	 * program by its name without an extension only with an extension that {@code PATHEXT} lists; and Codex's MXC
	 * sandbox, the only Windows sandbox that denies reads without an administrator's setup, does not start without
	 * {@code SystemDrive} and {@code LOCALAPPDATA}.
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
		copy(source, result, "NVM_DIR");
		for (String name : PROXY_VARIABLES)
		{
			copy(source, result, name);
			copy(source, result, name.toLowerCase(Locale.ROOT));
		}
		if (Platform.current().isWindows())
		{
			copy(source, result, "USERPROFILE");
			copy(source, result, "LOCALAPPDATA");
			copy(source, result, "TEMP");
			copy(source, result, "TMP");
			copy(source, result, "PATHEXT");
			copy(source, result, "SystemDrive");
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
	 * Gives a child process a home directory. The environment names it in {@code HOME} and, on Windows, also in
	 * {@code USERPROFILE}, which Windows programs read instead of {@code HOME}. On Windows, the environment also names
	 * the home's {@code AppData\Local} in {@code LOCALAPPDATA}, where Windows programs keep a user's local data and
	 * without which Codex's MXC sandbox does not start; the folder need not exist.
	 *
	 * @param environment the mutable isolated environment
	 *
	 * @param home the child's home directory
	 */
	public static void setHomeDirectory(Map<String, String> environment, Path home)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(home, "home").isNotNull();
		environment.putAll(homeVariables(home, Platform.current()));
	}

	/**
	 * Returns the variables that give a child process a home directory on a platform, as
	 * {@link #setHomeDirectory(Map, Path)} sets them: {@code HOME} and, on Windows, also {@code USERPROFILE} and
	 * {@code LOCALAPPDATA}.
	 *
	 * @param home     the child's home directory
	 * @param platform the platform that the child runs on
	 * @return each variable's name mapped to its value
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Map<String, String> homeVariables(Path home, Platform platform)
	{
		requireThat(home, "home").isNotNull();
		requireThat(platform, "platform").isNotNull();
		Map<String, String> variables = new HashMap<>();
		variables.put("HOME", home.toString());
		variables.put(platform.homeVariable(), home.toString());
		if (platform.isWindows())
			variables.put("LOCALAPPDATA", home.resolve("AppData").resolve("Local").toString());
		return Map.copyOf(variables);
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
