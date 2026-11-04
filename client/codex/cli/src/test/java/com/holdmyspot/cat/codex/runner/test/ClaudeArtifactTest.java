package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.ClaudeArtifact;
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
 * Tests {@link ClaudeArtifact}, which finds the Claude CAT plugin that the Codex plugin's {@code claude-runner}
 * launcher runs.
 */
public final class ClaudeArtifactTest
{
	/**
	 * The full plugin version of the Codex plugin that runs the selector.
	 */
	private static final String OWN_VERSION = "1.0+b0123456789ab";

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
	 * Returns the command, as the selector quotes it, that installs CAT into Claude Code from a Codex plugin, on the
	 * current platform.
	 *
	 * @param plugin the Codex plugin's version folder
	 * @return the command
	 */
	private static String installCommand(Path plugin)
	{
		if (Platform.current().isWindows())
		{
			// The version folder's "+" makes PowerShell need the quoted path and its call operator
			String installer = plugin.resolve("bin/installer.ps1").toString().replace("'", "''");
			return "& '" + installer + "' install --harness claude";
		}
		return plugin.resolve("bin/installer") + " install --harness claude";
	}

	/**
	 * Creates the Codex plugin that runs the selector.
	 *
	 * @param root the test's directory
	 * @return the plugin's version folder
	 * @throws IOException if the plugin cannot be written
	 */
	private static Path ownPlugin(Path root) throws IOException
	{
		return FakeRelease.artifact(root.resolve("codex/plugins/cache/cat/cat").resolve(OWN_VERSION),
			".codex-plugin/plugin.json", "codex-runner", OWN_VERSION, Platform.current()).toAbsolutePath().normalize();
	}

	/**
	 * Creates a Claude CAT plugin version folder, bound to a runtime, in a Claude Code configuration directory, and
	 * records it as installed for the user.
	 *
	 * @param root          the test's directory
	 * @param configuration the Claude Code configuration directory
	 * @param version       the plugin version
	 * @return the version folder
	 * @throws IOException if the plugin or its record cannot be written
	 */
	private static Path installClaude(Path root, Path configuration, String version) throws IOException
	{
		Path installed = versionFolder(root, configuration, version);
		record(configuration, version);
		return installed;
	}

	/**
	 * Creates a Claude CAT plugin version folder, bound to a runtime, without recording it as installed.
	 *
	 * @param root          the test's directory
	 * @param configuration the Claude Code configuration directory
	 * @param version       the plugin version
	 * @return the version folder
	 * @throws IOException if the plugin cannot be written
	 */
	private static Path versionFolder(Path root, Path configuration, String version) throws IOException
	{
		Path installed = FakeRelease.artifact(configuration.resolve("plugins/cat-marketplace/plugins/cat").
			resolve(version), ".claude-plugin/plugin.json", "claude-runner", version, Platform.current()).
			toAbsolutePath().normalize();
		RuntimeBinding.write(installed, FakeRelease.runtime(root.resolve("runtime"), Platform.current()));
		return installed;
	}

	/**
	 * Writes Claude Code's install records, naming one user installation of CAT, as Claude Code writes them after it
	 * installs a plugin that it runs in place: the record's {@code installPath} names Claude Code's cache copy, in which
	 * the version's {@code +} becomes {@code -}.
	 *
	 * @param configuration the Claude Code configuration directory
	 * @param version       the plugin version
	 * @throws IOException if the records cannot be written
	 */
	private static void record(Path configuration, String version) throws IOException
	{
		Path records = configuration.resolve("plugins/installed_plugins.json");
		Files.createDirectories(records.getParent());
		String cache = configuration.resolve("plugins/cache/cat/cat").resolve(version.replace('+', '-')).toString();
		Files.writeString(records, """
			{
			  "version": 2,
			  "plugins": {
			    "cat@cat": [
			      {
			        "scope": "user",
			        "installPath": "%s",
			        "version": "%s",
			        "installedAt": "2026-11-04T14:02:15.151Z",
			        "lastUpdated": "2026-11-04T14:02:15.151Z"
			      }
			    ]
			  }
			}
			""".formatted(cache.replace("\\", "\\\\"), version));
	}

	/**
	 * Returns the environment of a user whose Claude Code configuration directory is {@code configuration}.
	 *
	 * @param root          the test's directory
	 * @param configuration the Claude Code configuration directory
	 * @return the environment
	 */
	private static Map<String, String> environment(Path root, Path configuration)
	{
		return Map.of(Platform.current().homeVariable(), root.resolve("home").toString(), "CLAUDE_CONFIG_DIR",
			configuration.toString());
	}

	/**
	 * Verifies that the version folder of the Claude CAT plugin that Claude Code's install records name, at this
	 * plugin's full version, is used.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesInstalledClaudePluginOfSameBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-installed-"));
			Path configuration = root.resolve("claude configuration");
			Path installed = installClaude(root, configuration, OWN_VERSION);

			String output = ClaudeArtifact.execute(environment(root, configuration), ownPlugin(root),
				Platform.current());

			requireThat(output, "output").isEqualTo("artifact=" + installed + "\nrunner=" + runner(installed) +
				"\nsource=installed\n");
		}
	}

	/**
	 * Verifies that the recorded Claude CAT plugin of another build of the same release is used, and that a version
	 * folder that the records do not name is not.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void usesInstalledClaudePluginOfAnotherBuild() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-build-"));
			Path configuration = root.resolve("claude");
			versionFolder(root, configuration, OWN_VERSION);
			Path installed = installClaude(root, configuration, "1.0+bfedcba987654");

			String output = ClaudeArtifact.execute(environment(root, configuration), ownPlugin(root),
				Platform.current());

			requireThat(output, "output").isEqualTo("artifact=" + installed + "\nrunner=" + runner(installed) +
				"\nsource=installed\n");
		}
	}

	/**
	 * Verifies that without CAT in Claude Code the selector stops with a message that says CAT will be installed into
	 * Claude Code and names the command, in this plugin's version folder, for the user to approve, and that it creates
	 * no bundle directory.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesWithoutCatInClaude() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-absent-"));
			Path plugin = ownPlugin(root);

			IOException refusal = refusal(environment(root, root.resolve("claude")), plugin);

			requireThat(refusal.getMessage(), "message").contains("CAT will be installed into Claude Code").
				contains(installCommand(plugin));
			requireThat(Files.exists(root.resolve("home/.cat/bundles")), "bundlesExist").isFalse();
		}
	}

	/**
	 * Verifies that a Claude CAT plugin of another release is refused with a message that names the same command,
	 * which updates CAT in Claude Code.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesClaudePluginOfAnotherRelease() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-release-"));
			Path configuration = root.resolve("claude");
			installClaude(root, configuration, "2.0+b0123456789ab");
			Path plugin = ownPlugin(root);

			IOException refusal = refusal(environment(root, configuration), plugin);

			requireThat(refusal.getMessage(), "message").contains("2.0+b0123456789ab").contains("1.0").
				contains(installCommand(plugin));
		}
	}

	/**
	 * Verifies that a Claude CAT plugin left in the marketplace folder itself, outside any version folder, is never
	 * chosen.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void ignoresMarketplaceFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-marketplace-"));
			Path configuration = root.resolve("claude");
			Path marketplace = FakeRelease.artifact(configuration.resolve("plugins/cat-marketplace/plugins/cat"),
				".claude-plugin/plugin.json", "claude-runner", OWN_VERSION, Platform.current());
			RuntimeBinding.write(marketplace, FakeRelease.runtime(root.resolve("runtime"), Platform.current()));
			Path plugin = ownPlugin(root);

			IOException refusal = refusal(environment(root, configuration), plugin);

			requireThat(refusal.getMessage(), "message").contains(installCommand(plugin));
		}
	}

	/**
	 * Runs the selector in an environment where it must refuse.
	 *
	 * @param environment the environment
	 * @param plugin      the Codex plugin that runs the selector
	 * @return the refusal
	 * @throws AssertionError if the selector reports a plugin
	 */
	private static IOException refusal(Map<String, String> environment, Path plugin)
	{
		try
		{
			String output = ClaudeArtifact.execute(environment, plugin, Platform.current());
			throw new AssertionError("The selector reported a plugin that it must refuse:\n" + output);
		}
		catch (IOException e)
		{
			return e;
		}
	}
}
