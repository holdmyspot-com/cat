package com.holdmyspot.cat.claude.policy;

import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * A Claude Code session whose sandbox policy CAT reads, as a hook of the session finds it.
 *
 * @param processId               the ID of the session's Claude Code process
 * @param projectDirectory        the absolute project folder, which is the folder that the session started in
 * @param userHome                the user's absolute home folder
 * @param configurationDirectory  the absolute folder of Claude Code's configuration and user settings
 * @param temporaryDirectory      the absolute temporary folder that Claude Code gives commands, if the environment
 *                                names one
 * @param managedDirectory        the absolute folder of the managed settings
 * @param otherManagedDirectories the absolute folders of managed settings that Claude Code reads only in some
 *                                configurations, so that their effect is uncertain
 * @param unreadableManagedSources the absolute files that may hold managed settings in a form that CAT cannot read
 */
public record ClaudeSession(long processId, Path projectDirectory, Path userHome, Path configurationDirectory,
	Optional<Path> temporaryDirectory, Path managedDirectory, List<Path> otherManagedDirectories,
	List<Path> unreadableManagedSources)
{
	/**
	 * The fix of a refusal that the hook environment causes.
	 */
	private static final String ENVIRONMENT_FIX = "Start the session with Claude Code " + ClaudeRelease.VERSION +
		" or later";

	/**
	 * Creates a session.
	 *
	 * @param processId                the ID of the session's Claude Code process
	 * @param projectDirectory         the absolute project folder, which is the folder that the session started in
	 * @param userHome                 the user's absolute home folder
	 * @param configurationDirectory   the absolute folder of Claude Code's configuration and user settings
	 * @param temporaryDirectory       the absolute temporary folder that Claude Code gives commands, if the
	 *                                 environment names one
	 * @param managedDirectory         the absolute folder of the managed settings
	 * @param otherManagedDirectories  the absolute folders of managed settings that Claude Code reads only in some
	 *                                 configurations, so that their effect is uncertain
	 * @param unreadableManagedSources the absolute files that may hold managed settings in a form that CAT cannot
	 *                                 read
	 * @throws NullPointerException     if any of the arguments are null, or a list contains null
	 * @throws IllegalArgumentException if {@code processId} is not positive, or a path is not absolute
	 */
	public ClaudeSession
	{
		requireThat(processId, "processId").isPositive();
		requireAbsolute(projectDirectory, "projectDirectory");
		requireAbsolute(userHome, "userHome");
		requireAbsolute(configurationDirectory, "configurationDirectory");
		requireThat(temporaryDirectory, "temporaryDirectory").isNotNull();
		temporaryDirectory.ifPresent(path -> requireAbsolute(path, "temporaryDirectory"));
		requireAbsolute(managedDirectory, "managedDirectory");
		otherManagedDirectories = List.copyOf(otherManagedDirectories);
		otherManagedDirectories.forEach(path -> requireAbsolute(path, "otherManagedDirectories"));
		unreadableManagedSources = List.copyOf(unreadableManagedSources);
		unreadableManagedSources.forEach(path -> requireAbsolute(path, "unreadableManagedSources"));
	}

	/**
	 * Checks that a path is absolute.
	 *
	 * @param path the path
	 * @param name the name of the path
	 * @throws NullPointerException     if {@code path} is null
	 * @throws IllegalArgumentException if {@code path} is not absolute
	 */
	private static void requireAbsolute(Path path, String name)
	{
		requireThat(path, name).isNotNull();
		if (!path.isAbsolute())
			throw new IllegalArgumentException(name + " must be absolute: " + path);
	}

	/**
	 * Returns the session that runs a hook, from the hook's environment and the computer's operating system.
	 *
	 * @param environment the hook's environment variables
	 * @param userHome    the user's absolute home folder
	 * @param userName    the user's login name
	 * @return the session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code userHome} is not absolute
	 * @throws PolicyRefusal            if the environment does not name the session's process or project folder
	 */
	public static ClaudeSession fromHookEnvironment(Map<String, String> environment, Path userHome, String userName)
		throws PolicyRefusal
	{
		requireThat(environment, "environment").isNotNull();
		requireAbsolute(userHome, "userHome");
		requireThat(userName, "userName").isNotNull();
		long processId = processId(environment.get("CLAUDE_PID"));
		Path projectDirectory = absolute(environment, "CLAUDE_PROJECT_DIR").orElseThrow(() ->
			new PolicyRefusal("the hook environment does not name the session's project folder in " +
				"CLAUDE_PROJECT_DIR", ENVIRONMENT_FIX));
		Path configurationDirectory = userHome.resolve(".claude");
		String configuration = environment.get("CLAUDE_CONFIG_DIR");
		if (configuration != null && !configuration.isBlank())
		{
			configurationDirectory = absolute(environment, "CLAUDE_CONFIG_DIR").orElseThrow(() ->
				new PolicyRefusal("the hook environment's CLAUDE_CONFIG_DIR \"" + configuration + "\" is not an " +
					"absolute path", "Set CLAUDE_CONFIG_DIR to an absolute path, then start a new session"));
		}
		Optional<Path> temporaryDirectory = absolute(environment, ClaudeRelease.TEMPORARY_DIRECTORY_VARIABLE);

		boolean macOs = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
		Path managedDirectory;
		List<Path> otherManagedDirectories = new ArrayList<>();
		List<Path> unreadableManagedSources = new ArrayList<>();
		if (macOs)
		{
			managedDirectory = ClaudeRelease.MACOS_MANAGED_DIRECTORY;
			for (String preference : ClaudeRelease.MACOS_MANAGED_PREFERENCES)
				unreadableManagedSources.add(Path.of(preference.formatted(userName)));
		}
		else
		{
			managedDirectory = ClaudeRelease.LINUX_MANAGED_DIRECTORY;
			otherManagedDirectories.add(ClaudeRelease.WSL_WINDOWS_MANAGED_DIRECTORY);
		}
		return new ClaudeSession(processId, projectDirectory, userHome, configurationDirectory, temporaryDirectory,
			managedDirectory, otherManagedDirectories, unreadableManagedSources);
	}

	/**
	 * Returns the process ID that the hook environment gives.
	 *
	 * @param value the value of {@code CLAUDE_PID}, or null
	 * @return the process ID
	 * @throws PolicyRefusal if the value is absent or not a process ID
	 */
	private static long processId(String value) throws PolicyRefusal
	{
		if (value == null || value.isBlank())
		{
			throw new PolicyRefusal("the hook environment does not name the session's Claude Code process in " +
				"CLAUDE_PID", ENVIRONMENT_FIX);
		}
		try
		{
			long processId = Long.parseLong(value.strip());
			if (processId > 0)
				return processId;
		}
		catch (NumberFormatException _)
		{
			// Refused below
		}
		throw new PolicyRefusal("the hook environment's CLAUDE_PID \"" + value + "\" is not a process ID",
			ENVIRONMENT_FIX);
	}

	/**
	 * Returns the absolute path that an environment variable names.
	 *
	 * @param environment the environment variables
	 * @param name        the name of the variable
	 * @return the path, or empty if the variable is absent, blank, or not an absolute path
	 */
	private static Optional<Path> absolute(Map<String, String> environment, String name)
	{
		String value = environment.get(name);
		if (value == null || value.isBlank())
			return Optional.empty();
		try
		{
			Path path = Path.of(value);
			if (path.isAbsolute())
				return Optional.of(path.normalize());
		}
		catch (InvalidPathException _)
		{
			// Treated as absent
		}
		return Optional.empty();
	}
}
