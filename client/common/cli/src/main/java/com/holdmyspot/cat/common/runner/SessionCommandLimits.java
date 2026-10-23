package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The limits of the commands that a session runs, the same in every harness. A session's commands run with the run's
 * isolated home as their home directory, receive no harness credential in their environment, and cannot read any
 * harness's login or configuration files, in the run's home or the user's, nor any run under the run root except
 * their own. A harness that a command starts directly therefore finds no login and cannot reach the model provider.
 * <p>
 * The user's harness directories stay readable apart from those files, because the plugins installed in a session
 * link to files there. Each runner applies these limits through its harness's own sandbox settings and environment.
 */
public final class SessionCommandLimits
{
	/**
	 * The environment variables that carry a harness's credentials: Claude Code's API key, authentication token and
	 * OAuth token, and the Codex and OpenAI API keys that Codex reads. A session's commands receive none of them.
	 */
	public static final Set<String> CREDENTIAL_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"CLAUDE_CODE_OAUTH_TOKEN", "CODEX_API_KEY", "OPENAI_API_KEY");
	/**
	 * The files of Claude Code's configuration directory that hold its login and settings.
	 */
	private static final List<String> CLAUDE_FILES = List.of(".credentials.json", "settings.json",
		"settings.local.json");
	/**
	 * The file that holds Claude Code's account state and per-project configuration: in the home directory, or in the
	 * configuration directory when {@code CLAUDE_CONFIG_DIR} names it.
	 */
	private static final String CLAUDE_STATE = ".claude.json";
	/**
	 * The files of the Codex home that hold its login and configuration.
	 */
	private static final List<String> CODEX_FILES = List.of("auth.json", "config.toml");

	/**
	 * Returns the variables that make a run's isolated home the home directory of a session's commands: {@code HOME}
	 * and, on Windows, also {@code USERPROFILE} and {@code LOCALAPPDATA}.
	 *
	 * @param runHome  the run's isolated home
	 * @param platform the platform that the session runs on
	 * @return each variable's name mapped to its value
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Map<String, String> homeEnvironment(Path runHome, Platform platform)
	{
		return HarnessEnvironment.homeVariables(runHome, platform);
	}

	/**
	 * Returns the login and configuration files of every harness that an environment names, which a session's
	 * commands must not read: Claude Code's {@code .credentials.json}, {@code settings.json} and
	 * {@code settings.local.json} in its configuration directory and its {@code .claude.json}, and Codex's
	 * {@code auth.json} and {@code config.toml} in its home. The configuration directory is both the one that
	 * {@code CLAUDE_CONFIG_DIR} names and {@code .claude} in the home directory, and the Codex home is both the one that
	 * {@code CODEX_HOME} names and {@code .codex} in the home directory, so that a file stays denied whichever of them
	 * a harness started from a command would use. The result names files whether or not they exist; a runner leaves
	 * out those that its harness's sandbox cannot deny while they are missing.
	 *
	 * @param environment the environment of the user or of a session's harness, whose home variable on
	 *                    {@code platform} names the home directory
	 * @param platform    the platform whose home variable names the home directory
	 * @return the files, each once, absolute and normalized; empty if the environment names no home and no harness
	 *   directory
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static List<Path> harnessFiles(Map<String, String> environment, Platform platform)
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		Set<Path> files = new LinkedHashSet<>();
		Path home = directory(environment, platform.homeVariable());
		Path claudeDirectory = directory(environment, "CLAUDE_CONFIG_DIR");
		if (claudeDirectory != null)
		{
			addAll(files, claudeDirectory, CLAUDE_FILES);
			files.add(claudeDirectory.resolve(CLAUDE_STATE));
		}
		Path codexHome = directory(environment, "CODEX_HOME");
		if (codexHome != null)
			addAll(files, codexHome, CODEX_FILES);
		if (home != null)
		{
			addAll(files, home.resolve(".claude"), CLAUDE_FILES);
			files.add(home.resolve(CLAUDE_STATE));
			addAll(files, home.resolve(".codex"), CODEX_FILES);
		}
		return List.copyOf(files);
	}

	/**
	 * Adds files of a directory.
	 *
	 * @param files     the files
	 * @param directory the directory
	 * @param names     the names of the files in the directory
	 */
	private static void addAll(Set<Path> files, Path directory, List<String> names)
	{
		assert files != null;
		assert directory != null;
		assert names != null;
		for (String name : names)
			files.add(directory.resolve(name));
	}

	/**
	 * Returns the directory that a variable names.
	 *
	 * @param environment the environment
	 * @param variable    the variable
	 * @return the absolute, normalized directory, or {@code null} if the variable is unset or blank
	 */
	private static Path directory(Map<String, String> environment, String variable)
	{
		assert environment != null;
		assert variable != null;
		String value = environment.get(variable);
		if (value == null || value.isBlank())
			return null;
		return Path.of(value.strip()).toAbsolutePath().normalize();
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private SessionCommandLimits()
	{
	}
}
