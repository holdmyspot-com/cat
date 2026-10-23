package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.ClaudeArtifact;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeArtifact}, which finds the Claude CAT artifact that Codex's {@code claude-runner} skill runs.
 */
public final class ClaudeArtifactTest
{
	/**
	 * Creates a test instance.
	 */
	public ClaudeArtifactTest()
	{
	}

	/**
	 * Returns the Claude runner launcher's path in an artifact built for the current platform.
	 *
	 * @param artifact the artifact
	 * @return the runner's path
	 */
	private static Path runner(Path artifact)
	{
		if (Platform.current().isWindows())
			return artifact.resolve("bin/claude-runner.cmd");
		return artifact.resolve("bin/claude-runner");
	}

	/**
	 * Verifies that the Claude CAT plugin installed in {@code CLAUDE_CONFIG_DIR} is used when its version matches.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesInstalledClaudePlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-installed-"));
			Path configuration = root.resolve("claude configuration");
			Path installed = FakeRelease.artifact(configuration.resolve("plugins/cat-marketplace/plugins/cat"),
				".claude-plugin/plugin.json", "claude-runner", PluginInfo.CAT_VERSION, Platform.current()).
				toAbsolutePath().normalize();

			String output = ClaudeArtifact.execute(Map.of(Platform.current().homeVariable(),
				root.resolve("home").toString(), "CLAUDE_CONFIG_DIR", configuration.toString()));

			requireThat(output, "output").isEqualTo("artifact=" + installed + "\nrunner=" + runner(installed) +
				"\nsource=installed\n");
		}
	}

	/**
	 * Verifies that {@code CAT_CLAUDE_MARKETPLACE_ROOT}, which places CAT's Claude marketplace for the installer, also
	 * locates the installed plugin.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesMarketplaceOverride() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-marketplace-"));
			Path marketplace = root.resolve("cat-marketplace");
			Path installed = FakeRelease.artifact(marketplace.resolve("plugins/cat"), ".claude-plugin/plugin.json",
				"claude-runner", PluginInfo.CAT_VERSION, Platform.current()).toAbsolutePath().normalize();

			String output = ClaudeArtifact.execute(Map.of(Platform.current().homeVariable(),
				root.resolve("home").toString(), "CAT_CLAUDE_MARKETPLACE_ROOT", marketplace.toString()));

			requireThat(output, "output").contains("artifact=" + installed + "\n").endsWith("source=installed\n");
		}
	}

	/**
	 * Verifies that without the Claude CAT plugin, the Claude release archive of this CAT version and platform is
	 * downloaded from the release that {@code CAT_RELEASE_BASE_URL} names into CAT's per-user bundle directory.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void downloadsClaudeArchiveWithoutPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-download-"));
			String name = "cat-claude-v" + PluginInfo.CAT_VERSION + "-" + Platform.current().id();
			Path release = root.resolve("release");
			FakeRelease.publish(release, name, FakeRelease.artifact(root.resolve("source"),
				".claude-plugin/plugin.json", "claude-runner", PluginInfo.CAT_VERSION, Platform.current()));
			Path home = root.resolve("home");

			String output = ClaudeArtifact.execute(Map.of(Platform.current().homeVariable(), home.toString(),
				"CAT_RELEASE_BASE_URL", release.toUri().toString()));

			Path bundle = home.resolve(".cat/bundles").resolve(name).toAbsolutePath().normalize();
			requireThat(output, "output").isEqualTo("artifact=" + bundle + "\nrunner=" + runner(bundle) +
				"\nsource=download\n");
		}
	}
}
