package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeArtifact;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeArtifact}, which reports the Claude CAT plugin that holds it as the artifact that the Claude
 * plugin's {@code claude-runner} launcher runs.
 */
public final class ClaudeArtifactTest
{
	/**
	 * The full plugin version of the plugin that holds the selector.
	 */
	private static final String VERSION = "1.0+b0123456789ab";

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
	 * Verifies that the plugin that holds the selector is reported as the installed artifact, with its runner.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void reportsOwnPluginAsInstalled() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-own-"));
			Path plugin = FakeRelease.artifact(root.resolve("plugin"), ".claude-plugin/plugin.json", "claude-runner",
				VERSION, Platform.current()).toAbsolutePath().normalize();
			RuntimeBinding.write(plugin, FakeRelease.runtime(root.resolve("runtime"), Platform.current()));

			String output = ClaudeArtifact.execute(plugin);

			requireThat(output, "output").isEqualTo("artifact=" + plugin + "\nrunner=" + runner(plugin) +
				"\nsource=installed\n");
		}
	}

	/**
	 * Verifies that a plugin of another CAT release is refused with the reason instead of being reported.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesPluginOfAnotherVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-version-"));
			Path plugin = FakeRelease.artifact(root.resolve("plugin"), ".claude-plugin/plugin.json", "claude-runner",
				"2.0+b0123456789ab", Platform.current()).toAbsolutePath().normalize();
			RuntimeBinding.write(plugin, FakeRelease.runtime(root.resolve("runtime"), Platform.current()));

			IOException refusal = refusal(plugin);

			requireThat(refusal.getMessage(), "message").isEqualTo(
				"Artifact version 2.0+b0123456789ab is not of CAT release " + PluginInfo.CAT_VERSION + ": " + plugin);
		}
	}

	/**
	 * Verifies that a plugin without the Claude runner launcher is refused with the reason instead of being reported.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesPluginWithoutRunner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("claude-artifact-runner-"));
			Path plugin = FakeRelease.artifact(root.resolve("plugin"), ".claude-plugin/plugin.json", "codex-runner",
				VERSION, Platform.current()).toAbsolutePath().normalize();
			RuntimeBinding.write(plugin, FakeRelease.runtime(root.resolve("runtime"), Platform.current()));

			IOException refusal = refusal(plugin);

			requireThat(refusal.getMessage(), "message").isEqualTo(
				"The artifact lacks the executable runner launcher " + runner(plugin));
		}
	}

	/**
	 * Runs the selector on a plugin that it must refuse.
	 *
	 * @param plugin the plugin
	 * @return the refusal
	 * @throws AssertionError if the selector reports the plugin
	 */
	private static IOException refusal(Path plugin)
	{
		try
		{
			String output = ClaudeArtifact.execute(plugin);
			throw new AssertionError("The selector reported a plugin that it must refuse:\n" + output);
		}
		catch (IOException e)
		{
			return e;
		}
	}
}
