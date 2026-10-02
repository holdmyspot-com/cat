package io.github.cowwoc.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import org.testng.annotations.Test;

import java.nio.file.Path;

/**
 * Tests the shared host configuration-directory selection policy.
 */
public final class ClaudeConfigurationTest
{
	/**
	 * Creates the test.
	 */
	public ClaudeConfigurationTest()
	{
	}

	/**
	 * Verifies an explicit configuration directory takes precedence over the home fallback.
	 */
	@Test
	public void prefersExplicitDirectory()
	{
		requireThat(ClaudeConfiguration.resolveDirectory(Path.of("home"), "configuration"), "directory").
			isEqualTo(Path.of("configuration"));
	}

	/**
	 * Verifies absent and blank overrides select the same home subdirectory.
	 */
	@Test
	public void defaultsToHomeSubdirectory()
	{
		Path home = Path.of("home");
		requireThat(ClaudeConfiguration.resolveDirectory(home, null), "absent").isEqualTo(home.resolve(".claude"));
		Path blank = ClaudeConfiguration.resolveDirectory(home, "  ");
		requireThat(blank, "blank").isEqualTo(home.resolve(".claude"));
	}

	/**
	 * Verifies an override can locate configuration without a home directory.
	 */
	@Test
	public void acceptsOverrideWithoutHome()
	{
		requireThat(ClaudeConfiguration.resolveDirectory(null, "configuration"), "directory").
			isEqualTo(Path.of("configuration"));
	}

	/**
	 * Verifies directory selection preserves whitespace in non-blank path text.
	 */
	@Test
	public void preservesPathWhitespace()
	{
		Path directory = ClaudeConfiguration.resolveDirectory(null, " configuration ");
		requireThat(directory, "directory").isEqualTo(Path.of(" configuration "));
	}

	/**
	 * Verifies missing configuration and home are rejected.
	 */
	@Test(expectedExceptions = NullPointerException.class, expectedExceptionsMessageRegExp = ".*home.*")
	public void rejectsMissingDirectory()
	{
		ClaudeConfiguration.resolveDirectory(null, null);
	}
}
