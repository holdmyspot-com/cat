package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.CodexArtifact;
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
 * Tests {@link CodexArtifact}, which finds the Codex CAT artifact that Claude's {@code codex-runner} skill runs.
 */
public final class CodexArtifactTest
{
	/**
	 * Creates a test instance.
	 */
	public CodexArtifactTest()
	{
	}

	/**
	 * Returns the Codex runner launcher's path in an artifact built for the current platform.
	 *
	 * @param artifact the artifact
	 * @return the runner's path
	 */
	private static Path runner(Path artifact)
	{
		if (Platform.current().isWindows())
			return artifact.resolve("bin/codex-runner.cmd");
		return artifact.resolve("bin/codex-runner");
	}

	/**
	 * Verifies that the Codex CAT plugin installed in {@code CODEX_HOME} is used when its version matches.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesInstalledCodexPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-installed-"));
			Path codexHome = root.resolve("codex home");
			Path installed = FakeRelease.artifact(codexHome.resolve("plugins/cat-marketplace/plugins/cat"),
				".codex-plugin/plugin.json", "codex-runner", PluginInfo.CAT_VERSION, Platform.current()).
				toAbsolutePath().normalize();

			String output = CodexArtifact.execute(Map.of(Platform.current().homeVariable(), root.resolve("home").toString(),
				"CODEX_HOME", codexHome.toString()));

			requireThat(output, "output").isEqualTo("artifact=" + installed + "\nrunner=" + runner(installed) +
				"\nsource=installed\n");
		}
	}

	/**
	 * Verifies that {@code CAT_CODEX_MARKETPLACE_ROOT}, which places CAT's Codex marketplace for the installer, also
	 * locates the installed plugin.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesMarketplaceOverride() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-marketplace-"));
			Path marketplace = root.resolve("cat-marketplace");
			Path installed = FakeRelease.artifact(marketplace.resolve("plugins/cat"), ".codex-plugin/plugin.json",
				"codex-runner", PluginInfo.CAT_VERSION, Platform.current()).toAbsolutePath().normalize();

			String output = CodexArtifact.execute(Map.of(Platform.current().homeVariable(), root.resolve("home").toString(),
				"CAT_CODEX_MARKETPLACE_ROOT", marketplace.toString()));

			requireThat(output, "output").contains("artifact=" + installed + "\n").endsWith("source=installed\n");
		}
	}

	/**
	 * Verifies that without the Codex CAT plugin, the Codex release archive of this CAT version and platform is
	 * downloaded from the release that {@code CAT_RELEASE_BASE_URL} names into CAT's per-user bundle directory.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void downloadsCodexArchiveWithoutPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-download-"));
			String name = "cat-codex-v" + PluginInfo.CAT_VERSION + "-" + Platform.current().id();
			Path release = root.resolve("release");
			FakeRelease.publish(release, name, FakeRelease.artifact(root.resolve("source"), ".codex-plugin/plugin.json",
				"codex-runner", PluginInfo.CAT_VERSION, Platform.current()));
			Path home = root.resolve("home");

			String output = CodexArtifact.execute(Map.of(Platform.current().homeVariable(), home.toString(),
				"CAT_RELEASE_BASE_URL", release.toUri().toString()));

			Path bundle = home.resolve(".cat/bundles").resolve(name).toAbsolutePath().normalize();
			requireThat(output, "output").isEqualTo("artifact=" + bundle + "\nrunner=" + runner(bundle) +
				"\nsource=download\n");
		}
	}
}
