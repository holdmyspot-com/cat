package com.holdmyspot.cat.claude.runner.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.ClaudeSessions;
import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.port.contract.test.VersionRegistry;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("claude");
			manager.snapshotProject(run);
			Files.writeString(Files.createDirectories(run.directory().resolve("home/.claude")).
				resolve(".credentials.json"), "{}");
			Files.createDirectories(root.resolve("runs/workspaces"));
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
				claudeTemporaryDirectory.resolve("home"), ClaudeRunner.sessionDeniedReads(manager, run, List.of(),
					List.of(deniedFile, deniedDirectory)), Platform.LINUX_X64);

			// Run directories differ per run, so the hash covers the settings with their root and the run's identifier
			// replaced
			String settings = replaceRoot(Files.readString(destination, UTF_8).replace("\r\n", "\n"), root).
				replace(run.id(), "<run>");
			RunnerSessions.Settings sessionSettings = new RunnerSessions.Settings(root, root, List::of, "", false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			int version = new ClaudeSessions(sessionSettings).sessionEnvironmentVersion();
			VersionRegistry.requireRegistered(REGISTRY, "session environment", version,
				VersionRegistry.hash(settings));
		}
	}

	/**
	 * Replaces each path below a root in the settings with {@code <root>} followed by the path's names, separated by
	 * {@code /}, so that every platform hashes the same content.
	 *
	 * @param settings the settings
	 * @param root     the root, which the settings name inside JSON strings that escape each backslash, or in the
	 *                 form that a permission rule names it
	 * @return the settings with the root replaced
	 */
	private static String replaceRoot(String settings, Path root)
	{
		String escaped = root.toString().replace("\\", "\\\\");
		String result = Pattern.compile(Pattern.quote(escaped) + "([^\"]*)").matcher(settings).
			replaceAll(match -> Matcher.quoteReplacement("<root>" + match.group(1).replace("\\\\", "/")));
		return result.replace(ClaudeConnectionSettings.rulePath(root), "<root>");
	}
}
