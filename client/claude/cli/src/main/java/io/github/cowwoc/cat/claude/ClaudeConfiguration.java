package io.github.cowwoc.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * Resolves the host directory that holds Claude Code configuration and session transcripts.
 */
public final class ClaudeConfiguration
{
	/**
	 * Prevents utility-class construction.
	 */
	private ClaudeConfiguration()
	{
	}

	/**
	 * Selects a non-blank configuration override, otherwise the {@code .claude} directory below the supplied home.
	 * Path text is preserved, including leading or trailing whitespace in a non-blank override.
	 *
	 * @param home the caller's home directory, or {@code null} when only an explicit override is available
	 * @param configured the caller's {@code CLAUDE_CONFIG_DIR} value, or {@code null} or blank when unset
	 * @return the selected configuration directory
	 * @throws NullPointerException if {@code home} is null and {@code configured} is unset or blank
	 */
	public static Path resolveDirectory(Path home, String configured)
	{
		if (configured != null && !configured.isBlank())
			return Path.of(configured);
		requireThat(home, "home").isNotNull();
		return home.resolve(".claude");
	}
}
