package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.CodexArtifact;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexArtifact}, which finds the Codex CAT plugin that the Claude plugin's {@code codex-runner}
 * launcher runs.
 */
public final class CodexArtifactTest
{
	/**
	 * The full plugin version of the Claude plugin that runs the selector.
	 */
	private static final String OWN_VERSION = "1.0+b0123456789ab";

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
	 * Returns the command, as the selector quotes it, that runs a plugin's installer with
	 * {@code install --harness codex}, on the current platform.
	 *
	 * @param plugin the version folder whose installer runs the command
	 * @return the command
	 */
	private static String installCommand(Path plugin)
	{
		if (Platform.current().isWindows())
		{
			// The version folder's "+" makes PowerShell need the quoted path and its call operator
			String installer = plugin.resolve("bin/installer.ps1").toString().replace("'", "''");
			return "& '" + installer + "' install --harness codex";
		}
		return plugin.resolve("bin/installer") + " install --harness codex";
	}

	/**
	 * Creates the Claude plugin that runs the selector.
	 *
	 * @param root the test's directory
	 * @return the plugin's version folder
	 * @throws IOException if the plugin cannot be written
	 */
	private static Path ownPlugin(Path root) throws IOException
	{
		return FakeRelease.artifact(root.resolve("claude/plugins/cat-marketplace/plugins/cat").resolve(OWN_VERSION),
			".claude-plugin/plugin.json", "claude-runner", OWN_VERSION, Platform.current()).toAbsolutePath().
			normalize();
	}

	/**
	 * Installs a Codex CAT plugin version folder, bound to a runtime, in a Codex home.
	 *
	 * @param root      the test's directory
	 * @param codexHome the Codex home
	 * @param version   the plugin version
	 * @return the version folder
	 * @throws IOException if the plugin cannot be written
	 */
	private static Path installCodex(Path root, Path codexHome, String version) throws IOException
	{
		Path installed = FakeRelease.artifact(codexHome.resolve("plugins/cache/cat/cat").resolve(version),
			".codex-plugin/plugin.json", "codex-runner", version, Platform.current()).toAbsolutePath().normalize();
		RuntimeBinding.write(installed, FakeRelease.runtime(root.resolve("runtime"), Platform.current()));
		return installed;
	}

	/**
	 * Returns the environment of a user whose Codex home is {@code codexHome}.
	 *
	 * @param root      the test's directory
	 * @param codexHome the Codex home
	 * @return the environment
	 */
	private static Map<String, String> environment(Path root, Path codexHome)
	{
		return Map.of(Platform.current().homeVariable(), root.resolve("home").toString(), "CODEX_HOME",
			codexHome.toString());
	}

	/**
	 * Verifies that the Codex CAT plugin of this plugin's full version, installed in {@code CODEX_HOME}'s plugin
	 * cache, is used.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesInstalledCodexPluginOfSameBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-installed-")).toRealPath();
			Path codexHome = root.resolve("codex home");
			Path installed = installCodex(root, codexHome, OWN_VERSION);

			String output = CodexArtifact.execute(environment(root, codexHome), ownPlugin(root), Platform.current());

			requireThat(output, "output").isEqualTo("artifact=" + installed + "\nrunner=" + runner(installed) +
				"\nsource=installed\n");
		}
	}

	/**
	 * Verifies that a Codex CAT plugin of another build of the same release is used.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesInstalledCodexPluginOfAnotherBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-build-")).toRealPath();
			Path codexHome = root.resolve("codex");
			Path installed = installCodex(root, codexHome, "1.0+bfedcba987654");

			String output = CodexArtifact.execute(environment(root, codexHome), ownPlugin(root), Platform.current());

			requireThat(output, "output").isEqualTo("artifact=" + installed + "\nrunner=" + runner(installed) +
				"\nsource=installed\n");
		}
	}

	/**
	 * Verifies that without CAT in Codex the selector stops with a message that says CAT will be installed into Codex
	 * and names the command, in this plugin's version folder, for the user to approve, and that it creates no bundle
	 * directory.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesWithoutCatInCodex() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-absent-"));
			Path plugin = ownPlugin(root);

			IOException refusal = refusal(environment(root, root.resolve("codex")), plugin);

			requireThat(refusal.getMessage(), "message").contains("CAT will be installed into Codex").
				contains(installCommand(plugin));
			requireThat(Files.exists(root.resolve("home/.cat/bundles")), "bundlesExist").isFalse();
		}
	}

	/**
	 * Verifies that a Codex CAT plugin of another release is refused with a message that names the command of that
	 * installation's own installer that updates it to this plugin's release.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesCodexPluginOfAnotherRelease() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-release-")).toRealPath();
			Path codexHome = root.resolve("codex");
			Path installed = installCodex(root, codexHome, "2.0+b0123456789ab");
			Path plugin = ownPlugin(root);

			IOException refusal = refusal(environment(root, codexHome), plugin);

			requireThat(refusal.getMessage(), "message").contains("2.0+b0123456789ab").contains("1.0").
				contains(installCommand(installed) + " v1.0");
		}
	}

	/**
	 * Verifies that a Codex CAT plugin in CAT's Codex marketplace folder, which Codex does not run, is never chosen.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void ignoresMarketplaceFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("codex-artifact-marketplace-"));
			Path codexHome = root.resolve("codex");
			Path marketplace = FakeRelease.artifact(codexHome.resolve("plugins/cat-marketplace/plugins/cat"),
				".codex-plugin/plugin.json", "codex-runner", OWN_VERSION, Platform.current());
			RuntimeBinding.write(marketplace, FakeRelease.runtime(root.resolve("runtime"), Platform.current()));
			Path plugin = ownPlugin(root);

			IOException refusal = refusal(environment(root, codexHome), plugin);

			requireThat(refusal.getMessage(), "message").contains(installCommand(plugin));
		}
	}

	/**
	 * Runs the selector in an environment where it must refuse.
	 *
	 * @param environment the environment
	 * @param plugin      the Claude plugin that runs the selector
	 * @return the refusal
	 * @throws AssertionError if the selector reports a plugin
	 */
	private static IOException refusal(Map<String, String> environment, Path plugin)
	{
		try
		{
			String output = CodexArtifact.execute(environment, plugin, Platform.current());
			throw new AssertionError("The selector reported a plugin that it must refuse:\n" + output);
		}
		catch (IOException e)
		{
			return e;
		}
	}
}
