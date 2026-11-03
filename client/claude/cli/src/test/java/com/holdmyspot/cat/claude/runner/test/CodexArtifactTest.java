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
 * Tests {@link CodexArtifact}, which finds the Codex CAT artifact that the Claude plugin's {@code codex-runner}
 * launcher runs.
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
	 * Verifies that without the Codex CAT plugin, an existing bundle of this CAT version and platform is used
	 * even when the environment names a different release source.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesCodexBundleWithoutPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-bundle-"));
			String name = "cat-codex-v" + PluginInfo.CAT_VERSION + "-" + Platform.current().id();
			Path home = root.resolve("home");
			Path bundle = home.resolve(".cat/bundles").resolve(name).toAbsolutePath().normalize();
			FakeRelease.artifact(bundle, ".codex-plugin/plugin.json", "codex-runner", PluginInfo.CAT_VERSION,
				Platform.current());

			String output = CodexArtifact.execute(Map.of(Platform.current().homeVariable(), home.toString(),
				"CAT_RELEASE_BASE_URL", root.resolve("missing-release").toUri().toString()));

			requireThat(output, "output").isEqualTo("artifact=" + bundle + "\nrunner=" + runner(bundle) +
				"\nsource=bundle\n");
		}
	}
}
