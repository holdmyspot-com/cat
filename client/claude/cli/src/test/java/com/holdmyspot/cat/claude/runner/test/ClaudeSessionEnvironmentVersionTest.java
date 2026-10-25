package com.holdmyspot.cat.claude.runner.test;


import com.holdmyspot.cat.common.fakes.test.Yielding;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
	 * that the runner writes for a fixed host configuration, for a trial and for a control trial, so that changing
	 * those settings requires a decision about the version.
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
			Path hostProject = Files.createDirectories(root.resolve("project"));
			Files.writeString(hostProject.resolve("CLAUDE.md"), "Follow the handbook.", UTF_8);
			HarnessRunManager manager = new HarnessRunManager(scope.fileLocks(), hostProject.resolve("runs"),
				Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("claude");
			manager.snapshotProject(run);
			Files.writeString(Files.createDirectories(run.directory().resolve("home/.claude")).
				resolve(".credentials.json"), "{}");
			Files.createDirectories(hostProject.resolve("runs/workspaces"));
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

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, run, List.of(),
				List.of(deniedFile, deniedDirectory, hostProject), Map.of());
			List<Path> dependencyCaches = List.of(root.resolve("host-home/.npm"), root.resolve("host-home/.m2"));
			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), dependencyCaches, run.directory(), deniedReads,
				Platform.LINUX_X64);
			Path controlDestination = root.resolve("isolated/control-settings.json");
			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, controlDestination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), dependencyCaches, run.directory(), deniedReads, "claude-md",
				Platform.LINUX_X64);

			String settings = normalize(scope.json(), destination, root, run) + "\n" +
				normalize(scope.json(), controlDestination, root, run);
			RunnerSessions.Settings sessionSettings = new RunnerSessions.Settings(scope, root, root, List::of, "", false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			int version = new ClaudeSessions(sessionSettings).sessionEnvironmentVersion();
			VersionRegistry.requireRegistered(REGISTRY, "session environment", version,
				VersionRegistry.hash(settings));
		}
	}

	/**
	 * Returns the content of a settings file with its root and its run's identifier replaced, because run directories
	 * differ per run.
	 *
	 * @param json     the JSON mapper that wrote the settings
	 * @param file     the settings file
	 * @param root     the root
	 * @param run      the run
	 * @return the normalized settings
	 * @throws IOException if the file cannot be read
	 */
	private static String normalize(JsonMapper json, Path file, Path root, HarnessRun run) throws IOException
	{
		// The mapper ends its lines with the platform's line separator
		return replaceRoot(withoutExcludesAboveRoot(json, Files.readString(file, UTF_8), root), root).
			replace(run.id(), "<run>").replace("\r\n", "\n");
	}

	/**
	 * Keeps only the instruction files that the settings exclude at or below a root, each with the root replaced by
	 * {@code <root>} and its names separated by {@code /}. The settings exclude the files of every directory above the
	 * run directory, and the directories above the root differ between machines.
	 *
	 * @param json     the JSON mapper that wrote the settings
	 * @param settings the settings
	 * @param root     the root
	 * @return the settings with the other excluded files removed
	 */
	private static String withoutExcludesAboveRoot(JsonMapper json, String settings, Path root)
	{
		ObjectNode tree = (ObjectNode) json.readTree(settings);
		String name = root.getFileName().toString();
		ArrayNode kept = json.createArrayNode();
		for (JsonNode exclude : tree.path("claudeMdExcludes"))
		{
			String text = exclude.asString();
			int start = text.indexOf(name);
			if (start >= 0)
				kept.add("<root>" + text.substring(start + name.length()).replaceAll("\\\\+", "/"));
		}
		tree.set("claudeMdExcludes", kept);
		return json.writeValueAsString(tree);
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
