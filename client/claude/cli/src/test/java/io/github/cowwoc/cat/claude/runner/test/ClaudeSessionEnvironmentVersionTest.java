package io.github.cowwoc.cat.claude.runner.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.claude.optimizer.ClaudeSessions;
import io.github.cowwoc.cat.claude.runner.ClaudeConnectionSettings;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.VersionRegistry;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Guards the session environment version that {@link ClaudeSessions} declares.
 */
public final class ClaudeSessionEnvironmentVersionTest
{
	/**
	 * The registry of session environment versions and the hash of the isolated settings of each.
	 */
	private static final Path REGISTRY = Path.of("src/test/resources/optimizer/session-environment-versions.txt");

	/**
	 * Creates the test.
	 */
	public ClaudeSessionEnvironmentVersionTest()
	{
	}

	/**
	 * Verifies that the declared session environment version is registered with the hash of the isolated settings
	 * that the runner writes for a fixed host configuration, so that changing those settings requires a decision about
	 * the version.
	 *
	 * @throws IOException if the settings or the registry cannot be read or written
	 */
	@Test
	public void versionMatchesSettings() throws IOException
	{
		// Requirement: R-M1
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-session-environment-"));
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path deniedFile = Files.writeString(root.resolve("campaign.json"), "{}");
			Path deniedDirectory = Files.createDirectory(root.resolve("trials"));
			Files.writeString(source, """
				{
				  "env": {"ANTHROPIC_BASE_URL": "https://host.example"},
				  "sandbox": {
				    "network": {"allowedDomains": ["registry.npmjs.org"]},
				    "filesystem": {"allowWrite": ["/workspace"], "allowRead": ["/shared"], "denyRead": ["/secrets"]}
				  },
				  "permissions": {"allow": ["Bash(git status)"], "deny": ["Read(//secrets/**)"]},
				  "hooks": {"Stop": []}
				}
				""", UTF_8);

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(deniedFile, deniedDirectory));

			// Run directories differ per run, so the hash covers the settings with their root replaced
			String settings = Files.readString(destination, UTF_8).replace(root.toString(), "<root>");
			RunnerSessions.Settings sessionSettings = new RunnerSessions.Settings(root, root, List::of, "", false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			int version = new ClaudeSessions(sessionSettings).sessionEnvironmentVersion();
			VersionRegistry.requireRegistered(REGISTRY, "session environment", version,
				VersionRegistry.hash(settings));
		}
	}
}
