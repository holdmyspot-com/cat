package io.github.cowwoc.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.PluginManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

/**
 * Tests {@link PluginManifest}.
 */
public final class PluginManifestTest
{
	/**
	 * The Codex plugin manifest path.
	 */
	private static final Path MANIFEST = Path.of(".codex-plugin/plugin.json");

	/**
	 * Creates the test fixture.
	 */
	public PluginManifestTest()
	{
	}

	/**
	 * Verifies that a plugin's name comes from its manifest.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsName() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));
			write(plugin, "{\"name\": \"release-docs\", \"version\": \"1.0\"}");

			requireThat(PluginManifest.name(plugin, MANIFEST), "name").isEqualTo("release-docs");
		}
	}

	/**
	 * Verifies that a name that could leave the marketplace directory is refused.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*\\.\\./docs.*")
	public void rejectsPathName() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));
			write(plugin, "{\"name\": \"../docs\"}");

			PluginManifest.name(plugin, MANIFEST);
		}
	}

	/**
	 * Verifies that a plugin directory without the harness's manifest is refused.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*plugin\\.json.*")
	public void rejectsMissingManifest() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));

			PluginManifest.name(plugin, MANIFEST);
		}
	}

	/**
	 * Writes a plugin's manifest.
	 *
	 * @param plugin the plugin directory
	 * @param json the manifest's content
	 * @throws IOException if the manifest cannot be written
	 */
	private static void write(Path plugin, String json) throws IOException
	{
		Path manifest = plugin.resolve(MANIFEST);
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, json);
	}
}
