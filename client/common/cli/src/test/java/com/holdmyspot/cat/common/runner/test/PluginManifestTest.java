package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.PluginManifest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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
	 * Verifies that a plugin's version comes from its manifest, and that a manifest without a string version states
	 * none.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsVersion() throws IOException
	{
		// Requirement: R-W16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path versioned = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));
			write(versioned, "{\"name\": \"release-docs\", \"version\": \"1.0\"}");
			Path unversioned = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));
			write(unversioned, "{\"name\": \"release-docs\", \"version\": 1}");

			requireThat(PluginManifest.version(versioned, MANIFEST), "versioned").isEqualTo(Optional.of("1.0"));
			requireThat(PluginManifest.version(unversioned, MANIFEST), "unversioned").isEmpty();
		}
	}

	/**
	 * Verifies that a plugin's repository comes from its manifest, and that a manifest without a non-blank string
	 * repository states none.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsRepository() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path stated = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));
			write(stated, "{\"name\": \"release-docs\", \"repository\": \"https://example.com/docs\"}");
			Path missing = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));
			write(missing, "{\"name\": \"release-docs\"}");
			Path blank = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));
			write(blank, "{\"name\": \"release-docs\", \"repository\": \" \"}");
			Path object = cleanup.add(Files.createTempDirectory("cat-plugin-manifest-"));
			write(object, "{\"name\": \"release-docs\", \"repository\": {\"url\": \"https://example.com/docs\"}}");

			requireThat(PluginManifest.repository(stated, MANIFEST), "stated").
				isEqualTo(Optional.of("https://example.com/docs"));
			requireThat(PluginManifest.repository(missing, MANIFEST), "missing").isEmpty();
			requireThat(PluginManifest.repository(blank, MANIFEST), "blank").isEmpty();
			requireThat(PluginManifest.repository(object, MANIFEST), "object").isEmpty();
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
