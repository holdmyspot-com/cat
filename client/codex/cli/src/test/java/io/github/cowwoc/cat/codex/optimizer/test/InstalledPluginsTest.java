package io.github.cowwoc.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.optimizer.InstalledPlugins;
import io.github.cowwoc.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;

/**
 * Tests {@link InstalledPlugins} against a Codex home laid out as Codex lays it out.
 */
public final class InstalledPluginsTest
{
	/**
	 * The name of the plugin that the tests look up.
	 */
	private static final String NAME = "tools@market";

	/**
	 * Creates the test.
	 */
	public InstalledPluginsTest()
	{
	}

	/**
	 * Verifies that a recorded plugin with one cached version yields that copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsOneCopy() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-")).toRealPath();
			writeConfiguration(home, NAME);
			Path copy = pluginCopy(home, "1.0.0");

			requireThat(InstalledPlugins.of(home, NAME), "copies").isEqualTo(List.of(copy));
		}
	}

	/**
	 * Verifies that a plugin that the configuration does not record yields no copy, even when its files are cached.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsNoCopy() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-")).toRealPath();
			pluginCopy(home, "1.0.0");
			requireThat(InstalledPlugins.of(home, NAME), "withoutConfiguration").isEmpty();

			writeConfiguration(home, "other@market");
			requireThat(InstalledPlugins.of(home, NAME), "unrecorded").isEmpty();
		}
	}

	/**
	 * Verifies that a recorded plugin with two cached versions yields both copies, in path order.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsTwoCopies() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-")).toRealPath();
			writeConfiguration(home, NAME);
			Path second = pluginCopy(home, "2.0.0");
			Path first = pluginCopy(home, "1.0.0");

			requireThat(InstalledPlugins.of(home, NAME), "copies").isEqualTo(List.of(first, second));
		}
	}

	/**
	 * Writes a configuration that records one plugin as enabled.
	 *
	 * @param home the Codex home directory
	 * @param name the recorded plugin's name
	 * @throws IOException if the configuration cannot be written
	 */
	private static void writeConfiguration(Path home, String name) throws IOException
	{
		Files.writeString(home.resolve("config.toml"), "model = \"gpt\"\n\n[plugins.\"" + name + "\"]\n" +
			"enabled = true\n", UTF_8);
	}

	/**
	 * Creates a cached version of the plugin that holds its manifest.
	 *
	 * @param home the Codex home directory
	 * @param version the cached version
	 * @return the version's directory
	 * @throws IOException if the copy cannot be written
	 */
	private static Path pluginCopy(Path home, String version) throws IOException
	{
		Path copy = home.resolve("plugins/cache/market/tools").resolve(version);
		Files.createDirectories(copy.resolve(".codex-plugin"));
		Files.writeString(copy.resolve(".codex-plugin/plugin.json"), "{\"name\": \"tools\"}\n", UTF_8);
		return copy;
	}
}
