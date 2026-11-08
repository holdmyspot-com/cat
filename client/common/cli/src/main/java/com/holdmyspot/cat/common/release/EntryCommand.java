package com.holdmyspot.cat.common.release;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ShellQuoting;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes the CAT entry command that a message tells the user to run in a terminal. The user's terminal may have a
 * different home directory than the process that writes the message, such as an agent session whose {@code HOME} is not
 * the account's home. The entry script installs into the harness home that {@code HOME}, {@code CLAUDE_CONFIG_DIR} or
 * {@code CODEX_HOME} select, so the command sets each of those variables whose value the user's terminal may not have.
 */
public final class EntryCommand
{
	/**
	 * Each harness's variable that overrides its home, by harness identifier.
	 */
	private static final Map<String, String> HOME_VARIABLES = Map.of("claude", "CLAUDE_CONFIG_DIR", "codex",
		"CODEX_HOME");
	/**
	 * Each harness's home when its variable is unset, by harness identifier, relative to the user's home directory.
	 */
	private static final Map<String, Path> DEFAULT_HOMES = Map.of("claude", Path.of(".claude"), "codex",
		Path.of(".codex"));
	private final Platform platform;
	private final Map<String, String> environment;
	private final Path accountHome;

	/**
	 * Creates an entry command writer.
	 *
	 * @param platform    the platform that the running process belongs to
	 * @param environment the running process's environment
	 * @param accountHome the home directory that the user database records for the account that runs the process, or
	 *                    {@code null} if it cannot be determined
	 * @throws NullPointerException if {@code platform} or {@code environment} are null
	 */
	public EntryCommand(Platform platform, Map<String, String> environment, Path accountHome)
	{
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		this.platform = platform;
		this.environment = platform.environment(environment);
		this.accountHome = accountHome;
	}

	/**
	 * Returns the home directory that the user database records for the account that runs this process.
	 *
	 * @return the home directory, or {@code null} if the Java runtime could not determine it or it is not absolute
	 */
	public static Path currentAccountHome()
	{
		String home = System.getProperty("user.home");
		if (home == null || home.isBlank())
			return null;
		try
		{
			Path path = Path.of(home);
			if (path.isAbsolute())
				return path;
		}
		catch (InvalidPathException _)
		{
			// A value that is not a path is as unknown as an absent one
		}
		return null;
	}

	/**
	 * Returns the entry command that installs a harness's plugin, in the form for the platform that the running process
	 * belongs to: the PowerShell form on Windows, and the POSIX shell form elsewhere.
	 * <p>
	 * The command sets the harness's home variable when the process sets it to a directory other than the harness's
	 * default one in its home. Outside Windows, it also sets {@code HOME} to the process's value when that value differs
	 * from the account's home or the account's home is unknown. On Windows it does not set {@code HOME}: the
	 * PowerShell entry script, like other Windows programs, takes the user's home from the user profile. A value is
	 * quoted for the shell that runs the command.
	 *
	 * @param harness the harness
	 * @return the command
	 * @throws IllegalArgumentException if {@code harness} is blank
	 */
	public String forHarness(String harness)
	{
		requireThat(harness, "harness").isNotBlank();
		Map<String, String> assignments = new LinkedHashMap<>();
		String home = environment.get("HOME");
		if (!platform.isWindows() && home != null && !home.isBlank() && !isAccountHome(home))
			assignments.put("HOME", home);
		String variable = HOME_VARIABLES.get(harness);
		if (variable != null)
		{
			String value = environment.get(variable);
			if (value != null && !value.isEmpty() && !isDefaultHome(harness, value))
				assignments.put(variable, value);
		}
		if (platform.isWindows())
		{
			return assignments.entrySet().stream().
				map(assignment -> "$env:" + assignment.getKey() + "=" +
					ShellQuoting.powerShellString(assignment.getValue()) + "; ").
				collect(Collectors.joining()) + "cat.ps1 -Harness " + harness;
		}
		return assignments.entrySet().stream().
			map(assignment -> assignment.getKey() + "=" + ShellQuoting.posix(assignment.getValue()) + " ").
			collect(Collectors.joining()) + "cat.sh --harness " + harness;
	}

	/**
	 * Indicates whether a {@code HOME} value is the account's home directory.
	 *
	 * @param home the process's {@code HOME}
	 * @return {@code true} if {@code home} and the account's home name the same path once normalized; {@code false}
	 *   if they do not or the account's home is unknown
	 */
	private boolean isAccountHome(String home)
	{
		if (accountHome == null)
			return false;
		try
		{
			return Path.of(home).normalize().equals(accountHome.normalize());
		}
		catch (InvalidPathException _)
		{
			return false;
		}
	}

	/**
	 * Indicates whether a harness home variable's value is the directory that the entry script derives from the user's
	 * home when the variable is unset.
	 *
	 * @param harness the harness
	 * @param value   the variable's value
	 * @return {@code true} if the value is the harness's default directory in the process's home; {@code false} if it
	 *   is another directory or the process has no home to derive the default from
	 */
	private boolean isDefaultHome(String harness, String value)
	{
		String home = environment.get(platform.homeVariable());
		if (home == null || home.isBlank())
			return false;
		try
		{
			return Path.of(value).normalize().equals(Path.of(home).resolve(DEFAULT_HOMES.get(harness)).normalize());
		}
		catch (InvalidPathException _)
		{
			return false;
		}
	}
}
