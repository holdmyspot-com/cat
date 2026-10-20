package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeRunner}.
 */
public final class ClaudeRunnerTest
{
	/**
	 * Verifies the Claude launcher creates its Bubblewrap launch configuration beside the run-owned Claude temporary
	 * directory so run cleanup removes the workaround with the completed run.
	 */
	@Test
	public void resolvesRunLaunchConfiguration()
	{
		Path temporaryDirectory = Path.of("/system-temporary-directory/.cat/claude/claude/run-id/claude").toAbsolutePath();

		requireThat(ClaudeRunner.launchConfigurationPath(temporaryDirectory), "launchConfiguration").isEqualTo(
			Path.of("/system-temporary-directory/.cat/claude/claude/run-id/.claude/launch.json").toAbsolutePath());
	}

	/**
	 * Verifies an isolated home uses a credential file only when no usable environment authentication is available.
	 */
	@Test
	public void copiesCredentialFileWhenNeeded()
	{
		requireThat(ClaudeRunner.requiresCredentialFiles(Map.of()), "withoutAuthentication").isTrue();
		requireThat(ClaudeRunner.requiresCredentialFiles(Map.of("ANTHROPIC_API_KEY", "token")),
			"withAuthentication").isFalse();
	}

	/**
	 * Verifies an isolated one-shot session cannot retain or resume host conversation state or override its generated
	 * sandbox policy.
	 */
	@Test
	public void disablesSessionPersistence()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", Path.of("marketplace"), false,
			Path.of("hook-results"));

		requireThat(command, "command").contains("--no-session-persistence");
		requireThat(command, "command").doesNotContain("--settings");
	}

	/**
	 * Verifies a CAT run grants Claude access to spilled hook context without exposing the isolated home.
	 */
	@Test
	public void permitsOnlyHookResultsForSpilledContext()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", Path.of("marketplace"), true,
			Path.of("hook-results"));

		requireThat(command, "command").contains(Path.of("marketplace/plugins/cat").toString()).contains("hook-results").
			doesNotContain("home");
	}

	/**
	 * Verifies a trial plugin installs from the marketplace that bears its name.
	 */
	// Requirement: R-W12
	@Test
	public void installsTrialPluginByName()
	{
		List<List<String>> commands = ClaudeRunner.pluginSetupCommands("claude", Path.of("release-docs-marketplace"),
			"release-docs");

		requireThat(commands, "commands").isEqualTo(List.of(
			List.of("claude", "plugin", "marketplace", "add", "release-docs-marketplace"),
			List.of("claude", "plugin", "install", "release-docs@release-docs")));
	}

	/**
	 * Verifies a run with a trial plugin grants Claude access to that plugin and to spilled hook context.
	 */
	// Requirement: R-W12
	@Test
	public void permitsTrialPluginDirectory()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", "", "",
			List.of(Path.of("release-docs-marketplace/plugins/release-docs")), Path.of("hook-results"), List.of());

		requireThat(command, "command").contains(Path.of("release-docs-marketplace/plugins/release-docs").toString()).
			contains("hook-results");
	}

	/**
	 * Verifies that a trial installs exactly its listed plugin, from a marketplace of its own, and neither CAT nor any
	 * other plugin.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-W12
	@Test
	public void installsExactlyListedPlugin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-plugins-"));
			Path configuration = root.resolve("home/.claude");
			List<String> steps = new ArrayList<>();
			List<List<String>> commands = new ArrayList<>();

			List<Path> installed = ClaudeRunner.installPlugins("claude", plugin(root, "cat-artifact", "cat"), false,
				List.of(plugin(root, "docs-source", "release-docs")), configuration, (command, step) ->
				{
					commands.add(command);
					steps.add(step);
				});

			Path marketplace = configuration.resolve("plugins/release-docs-marketplace");
			requireThat(installed, "installed").isEqualTo(List.of(marketplace.resolve("plugins/release-docs")));
			requireThat(steps, "steps").isEqualTo(List.of("trial-plugin-release-docs-marketplace-add",
				"trial-plugin-release-docs-install"));
			requireThat(commands, "commands").isEqualTo(ClaudeRunner.pluginSetupCommands("claude", marketplace,
				"release-docs"));
			requireThat(Files.notExists(configuration.resolve("plugins/cat-marketplace")), "catMarketplace").isTrue();
		}
	}

	/**
	 * Verifies that a trial with two plugins installs both, in their listed order, each from its own marketplace.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-W12
	@Test
	public void installsTwoPlugins() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-plugins-"));
			Path configuration = root.resolve("home/.claude");
			List<String> steps = new ArrayList<>();

			List<Path> installed = ClaudeRunner.installPlugins("claude", plugin(root, "cat-artifact", "cat"), false,
				List.of(plugin(root, "docs-source", "release-docs"), plugin(root, "tools-source", "tools")),
				configuration, (_, step) -> steps.add(step));

			requireThat(installed, "installed").isEqualTo(List.of(
				configuration.resolve("plugins/release-docs-marketplace/plugins/release-docs"),
				configuration.resolve("plugins/tools-marketplace/plugins/tools")));
			requireThat(steps, "steps").isEqualTo(List.of("trial-plugin-release-docs-marketplace-add",
				"trial-plugin-release-docs-install", "trial-plugin-tools-marketplace-add", "trial-plugin-tools-install"));
			requireThat(Files.readString(installed.get(1).resolve("README.md")), "copy").isEqualTo("tools");
		}
	}

	/**
	 * Verifies that CAT is installed only when the trial lists it: a trial that lists no plugin installs nothing, and
	 * one that lists CAT installs CAT from the artifact under its own name.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// Requirement: R-W12
	@Test
	public void installsCatOnlyWhenListed() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-plugins-"));
			Path artifact = plugin(root, "cat-artifact", "cat");
			List<String> steps = new ArrayList<>();

			Path withoutCat = root.resolve("without/.claude");
			requireThat(ClaudeRunner.installPlugins("claude", artifact, false, List.of(), withoutCat,
				(_, step) -> steps.add(step)), "withoutCat").isEmpty();
			requireThat(steps, "withoutCatSteps").isEmpty();
			requireThat(Files.notExists(withoutCat), "withoutCatConfiguration").isTrue();

			Path withCat = root.resolve("with/.claude");
			List<Path> installed = ClaudeRunner.installPlugins("claude", artifact, true, List.of(), withCat,
				(_, step) -> steps.add(step));
			requireThat(installed, "withCat").isEqualTo(List.of(withCat.resolve("plugins/cat-marketplace/plugins/cat")));
			requireThat(steps, "withCatSteps").isEqualTo(List.of("marketplace-add", "plugin-install"));
		}
	}

	/**
	 * Creates a Claude Code plugin directory that holds a manifest and a readme.
	 *
	 * @param root the directory to create the plugin in
	 * @param directory the plugin directory's name
	 * @param name the manifest's plugin name, which the readme also holds
	 * @return the plugin directory
	 * @throws IOException if the plugin cannot be written
	 */
	private static Path plugin(Path root, String directory, String name) throws IOException
	{
		Path plugin = root.resolve(directory);
		Files.createDirectories(plugin.resolve(".claude-plugin"));
		Files.writeString(plugin.resolve(".claude-plugin/plugin.json"), "{\"name\": \"" + name + "\"}\n");
		Files.writeString(plugin.resolve("README.md"), name);
		return plugin;
	}

	/**
	 * Verifies a Claude worktree subagent can resolve its reported spill-context path without access beyond hook results.
	 */
	@Test
	public void aliasesClaudeReportedSpillContextPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path capture = cleanup.add(Files.createTempDirectory("claude-capture-"));
			Path directory = Files.createDirectories(capture.resolve("runs/claude/abc123-def456"));
			HarnessRun run = new HarnessRun("claude", "abc123-def456", directory);
			Path hookResults = ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), run);
			Path worktree = directory.resolve("project").toAbsolutePath().normalize();
			String canonicalName = worktree.toString().replaceAll("[^A-Za-z0-9_-]", "-");
			String parentName = capture.resolve("runs").toAbsolutePath().normalize().toString().replaceAll(
				"[^A-Za-z0-9_-]", "-");
			Path canonical = hookResults.resolve(canonicalName);
			Path alias = hookResults.resolve(parentName).resolve("claude/abc123-def456-project");

			requireThat(Files.isSymbolicLink(alias), "alias").isTrue();
			requireThat(Files.isSameFile(alias, canonical), "aliasTarget").isTrue();
		}
	}

	/**
	 * Verifies that a session is denied reading the runner's records of its own run, namely the run's artifacts,
	 * manifest, lock and initial project, the login that the run copied from the host, and the other runs and
	 * workspaces under the run root, besides the paths that the caller denies. The session can still read its project,
	 * the rest of its home, its Claude temporary directory and the evidence that it validates.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesReadingRunRecordsOtherRunsAndLogin() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize());
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("claude");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Files.createDirectories(directory.resolve("claude"));
			Path login = Files.writeString(Files.createDirectories(directory.resolve("home/.claude")).
				resolve(".credentials.json"), "{}");
			HarnessRun other = manager.create("claude");
			HarnessRun evidence = manager.create("claude");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path workspaces = Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
				List.of(heldOut));

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut,
				directory.resolve("artifacts"), directory.resolve("run.json"), directory.resolve(".lock"),
				directory.resolve("initial-project"), login, other.directory(), evidence.directory().resolve("project"),
				evidence.directory().resolve("home"), evidence.directory().resolve("run.json"),
				evidence.directory().resolve(".lock"), workspaces));
		}
	}

	/**
	 * Verifies that a session whose run root lies inside a project that the caller denies is denied every file and
	 * directory of that project except the directories that lead to its run and evidence, each path once. The session
	 * can still read its project, its home, its Claude temporary directory and the evidence that it validates, but not
	 * the files of the project around them.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesProjectAroundRunRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = Files.createDirectories(Path.of(System.getProperty("cat.session.temp")).normalize());
			Path project = cleanup.add(Files.createTempDirectory(temporary, "cat-user-project-"));
			Path instructions = Files.writeString(project.resolve("CLAUDE.md"), "Follow the handbook.");
			Path documents = Files.createDirectories(project.resolve("docs"));
			Path session = Files.createDirectories(project.resolve(".cat/sessions/session-1"));
			Path otherSession = Files.createDirectories(project.resolve(".cat/sessions/session-2"));
			Path campaigns = Files.createDirectories(session.resolve("campaigns"));
			Path root = Files.createDirectories(session.resolve("optimizer-runs/claude"));
			Path heldOutRoot = Files.createDirectories(session.resolve("optimizer-runs/claude-held_out"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("claude");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Files.createDirectories(directory.resolve("claude"));
			Files.createDirectories(directory.resolve("home"));
			HarnessRun evidence = manager.create("claude");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
				List.of(project));

			requireThat(deniedReads.size(), "deniedReads.size()").isEqualTo(Set.copyOf(deniedReads).size());
			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(instructions, documents,
				otherSession, campaigns, heldOutRoot, directory.resolve("artifacts"), directory.resolve("run.json"),
				directory.resolve(".lock"), directory.resolve("initial-project"),
				evidence.directory().resolve("project"), evidence.directory().resolve("home"),
				evidence.directory().resolve("run.json"), evidence.directory().resolve(".lock")));
		}
	}
}
