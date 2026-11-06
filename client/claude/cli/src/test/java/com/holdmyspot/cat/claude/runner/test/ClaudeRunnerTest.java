package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestInstructions;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ClaudeRunner}.
 */
public final class ClaudeRunnerTest
{
	/**
	 * The Claude Code variables that decide whether and how a session starts subagents.
	 */
	private static final List<String> SUBAGENT_VARIABLES = List.of("CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS",
		"CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH", "CLAUDE_CODE_SUBAGENT_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL_FORCE",
		"CLAUDE_CODE_SUBAGENT_EFFORT", "CLAUDE_CODE_FORK_SUBAGENT", "CLAUDE_CODE_DISABLE_EXPLORE_PLAN_AGENTS",
		"CLAUDE_AGENT_SDK_DISABLE_BUILTIN_AGENTS", "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS",
		"CLAUDE_CODE_PLAN_V2_AGENT_COUNT", "CLAUDE_CODE_PLAN_V2_EXPLORE_AGENT_COUNT",
		"CLAUDE_CODE_WORKFLOW_MAX_CONCURRENT_AGENTS", "CLAUDE_CODE_WORKFLOW_SUBAGENT_MODEL");

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
	 * Verifies that the Claude process environment does not ask Claude Code to scrub the environment and sandbox
	 * paths of its commands, even if the caller's environment does, so that the session's settings do not depend on
	 * the caller and Claude Code leaves the project's {@code .git/worktrees} to the session's grant.
	 *
	 * @throws IOException if the environment cannot be built
	 */
	@Test
	public void doesNotScrubSubprocessEnvironment() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-process-environment-"));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");
			environment.put("ANTHROPIC_API_KEY", "credential");
			environment.put("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB", "1");

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, home.resolve("missing-settings.json"), home, null);

			requireThat(result.keySet(), "variables").doesNotContain("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB");
			requireThat(result.get("ANTHROPIC_API_KEY"), "ANTHROPIC_API_KEY").isEqualTo("credential");
		}
	}

	/**
	 * Verifies that the Claude process environment marks the session as a CAT run, even when the caller's settings
	 * file sets the marker's variable otherwise, so that the session's SessionStart hook starts no session launcher.
	 *
	 * @throws IOException if the environment cannot be built
	 */
	@Test
	public void marksCatRun() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-run-marker-"));
			Path settings = Files.writeString(home.resolve("settings.json"), """
				{"env": {"%s": ""}}
				""".formatted(HarnessEnvironment.RUN_VARIABLE));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, settings, home, null);

			requireThat(result.get(HarnessEnvironment.RUN_VARIABLE), "marker").isEqualTo("1");
		}
	}

	/**
	 * Verifies that a Claude child needs the model host that the caller's settings name, the shared caches, and the
	 * caller's login file, which its isolated home copies when the caller's environment holds no Claude
	 * authentication variable.
	 *
	 * @throws IOException if the fixture or the report cannot be built
	 */
	@Test
	public void childNeedsModelHostCachesAndLogin() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-child-needs-")).toRealPath();
			Path configuration = Files.createDirectory(home.resolve(".claude"));
			Files.writeString(configuration.resolve("settings.json"), """
				{"env": {"ANTHROPIC_BASE_URL": "https://Gateway.Example:8443/v1"}}
				""");
			Path login = Files.writeString(configuration.resolve(".credentials.json"), "{}");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString());

			ChildNeedsReport needs = ClaudeRunner.childNeeds(scope, environment);

			requireThat(needs.hosts(), "hosts").isEqualTo(Set.of("gateway.example"));
			requireThat(needs.writes(), "writes").isEqualTo(List.of(home.resolve(".npm"), home.resolve(".m2")));
			requireThat(needs.logins(), "logins").isEqualTo(List.of(login));
		}
	}

	/**
	 * Verifies that a Claude child whose caller's environment holds an authentication variable needs no login file,
	 * since its isolated home copies none.
	 *
	 * @throws IOException if the fixture or the report cannot be built
	 */
	@Test
	public void childWithApiKeyNeedsNoLogin() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-child-needs-")).toRealPath();
			Path configuration = Files.createDirectory(home.resolve(".claude"));
			Files.writeString(configuration.resolve(".credentials.json"), "{}");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString(),
				"ANTHROPIC_API_KEY", "credential");

			ChildNeedsReport needs = ClaudeRunner.childNeeds(scope, environment);

			requireThat(needs.logins(), "logins").isEmpty();
			requireThat(needs.hosts(), "hosts").isEqualTo(Set.of("api.anthropic.com"));
		}
	}

	/**
	 * Verifies that a nested Claude process receives each of the parent's settings for starting subagents with the
	 * parent's value, so that it starts subagents the way its parent does.
	 *
	 * @throws IOException if the environment cannot be built
	 */
	@Test
	public void inheritsParentSubagentSettings() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-subagent-settings-"));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");
			for (String variable : SUBAGENT_VARIABLES)
				environment.put(variable, "parent-" + variable);

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, home.resolve("missing-settings.json"), home, null);

			for (String variable : SUBAGENT_VARIABLES)
				requireThat(result.get(variable), variable).isEqualTo("parent-" + variable);
		}
	}

	/**
	 * Verifies that a subagent setting that the parent's Claude settings file sets in its {@code env}, and its process
	 * environment does not, reaches the nested Claude process, while a value in the process environment wins over the
	 * settings file's.
	 *
	 * @throws IOException if the fixture or the environment cannot be built
	 */
	@Test
	public void inheritsSubagentSettingsFromHostSettings() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-subagent-host-settings-"));
			Path settings = Files.writeString(home.resolve("settings.json"), """
				{
				  "env": {
				    "CLAUDE_CODE_SUBAGENT_MODEL": "settings-model",
				    "CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS": "2"
				  }
				}
				""");
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");
			environment.put("CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS", "5");

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, settings, home, null);

			requireThat(result.get("CLAUDE_CODE_SUBAGENT_MODEL"), "settingsOnly").isEqualTo("settings-model");
			requireThat(result.get("CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS"), "processWins").isEqualTo("5");
		}
	}

	/**
	 * Verifies that a subagent setting that the parent sets nowhere stays unset in the nested Claude process, and that
	 * a Claude Code variable that does not control subagents does not reach it from either source.
	 *
	 * @throws IOException if the fixture or the environment cannot be built
	 */
	@Test
	public void copiesOnlySetSubagentSettings() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-unset-subagent-settings-"));
			Path settings = Files.writeString(home.resolve("settings.json"), """
				{"env": {"CLAUDE_CODE_DISABLE_TERMINAL_TITLE": "1"}}
				""");
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");
			environment.put("CLAUDE_CODE_EFFORT_LEVEL", "high");

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, settings, home, null);

			requireThat(result.keySet(), "variables").doesNotContainAny(SUBAGENT_VARIABLES).
				doesNotContain("CLAUDE_CODE_EFFORT_LEVEL").doesNotContain("CLAUDE_CODE_DISABLE_TERMINAL_TITLE");
		}
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
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", Path.of("marketplace"),
			false, Path.of("hook-results"), List.of());

		requireThat(command, "command").contains("--no-session-persistence");
		requireThat(command, "command").doesNotContain("--settings");
	}

	/**
	 * Verifies that a run asked for session transcripts lets Claude persist its session, and still sets no generated
	 * sandbox policy of its own.
	 */
	@Test
	public void persistsSessionForTranscripts()
	{
		// Requirement: R-I7
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of(), true);

		requireThat(command, "command").doesNotContain("--no-session-persistence").doesNotContain("--settings");
		requireThat(ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"), List.of(),
			false), "withoutTranscripts").contains("--no-session-persistence");
	}

	/**
	 * Verifies that the session runs in the {@code acceptEdits} permission mode, in which the sandbox confines its
	 * commands and its edits stay inside the project.
	 */
	@Test
	public void selectsAcceptEditsPermissionMode()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of(), false, null, 0);

		int option = command.indexOf("--permission-mode");
		requireThat(option, "option").isGreaterThan(-1);
		requireThat(command.get(option + 1), "permissionMode").isEqualTo("acceptEdits");
	}

	/**
	 * Verifies that the effort {@code none} starts Claude Code with extended thinking turned off and without
	 * {@code --effort}, which Claude Code would ignore for {@code none} and replace with its default effort, while
	 * every other effort is passed through as {@code --effort}.
	 */
	@Test
	public void noneEffortTurnsThinkingOff()
	{
		// Requirement: R-I11
		List<String> none = ClaudeRunner.command("claude", "sonnet", "none", "", List.of(), Path.of("hook-results"),
			List.of(), false);
		List<String> low = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of(), false);

		int thinking = none.indexOf("--thinking");
		requireThat(thinking, "thinking").isNotNegative();
		requireThat(none.get(thinking + 1), "thinkingMode").isEqualTo("disabled");
		requireThat(none, "none").doesNotContain("--effort").doesNotContain("none");
		int effort = low.indexOf("--effort");
		requireThat(effort, "effort").isNotNegative();
		requireThat(low.get(effort + 1), "effortLevel").isEqualTo("low");
		requireThat(low, "low").doesNotContain("--thinking");
	}

	/**
	 * Verifies that a session that may request sessions is told how through its appended system prompt, and that a
	 * session that may request none is told nothing.
	 */
	@Test
	public void explainsSessionRequests()
	{
		// Requirement: R-I9
		Path launcher = Path.of("/artifact with spaces/bin/request-session").toAbsolutePath();
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of(), false, launcher, 2);
		List<String> without = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of(), false, null, 0);

		int option = command.indexOf("--append-system-prompt");
		requireThat(option, "option").isNotNegative();
		requireThat(command.get(option + 1), "systemPrompt").
			isEqualTo(SessionRequestInstructions.text(2, launcher, false)).doesNotContain("$");
		requireThat(without, "without").doesNotContain("--append-system-prompt");
	}

	/**
	 * Verifies a CAT run grants Claude access to spilled hook context without exposing the isolated home.
	 */
	@Test
	public void permitsOnlyHookResultsForSpilledContext()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", Path.of("marketplace"),
			true, Path.of("hook-results"), List.of());

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
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "",
			List.of(Path.of("release-docs-marketplace/plugins/release-docs")), Path.of("hook-results"), List.of(), false);

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
	 * Verifies project-directory names match Claude Code 2.1.317, including its 200-character boundary and UTF-16
	 * replacement semantics.
	 *
	 * @param input       the project path supplied to Claude
	 * @param unixName    the native Claude name when the path uses forward slashes
	 * @param windowsName the native Claude name when the path uses backslashes
	 */
	@Test(dataProvider = "nativeProjectDirectoryNames")
	public void mapsNativeProjectDirectoryNames(String input, String unixName, String windowsName)
	{
		Path worktree = Path.of(input);
		String expected = unixName;
		if (worktree.getFileSystem().getSeparator().equals("\\"))
			expected = windowsName;

		requireThat(ClaudeRunner.claudeProjectDirectory(worktree), "projectDirectory").isEqualTo(expected);
	}

	/**
	 * Supplies names recorded from Claude Code 2.1.317's project-directory operation for both separator conventions.
	 *
	 * @return native input and output pairs
	 */
	@DataProvider
	public Object[][] nativeProjectDirectoryNames()
	{
		String boundaryPrefix = "-" + "a".repeat(199);
		return new Object[][]
		{
			{"/work/short-path", "-work-short-path", "-work-short-path"},
			{"/work/with_under_score", "-work-with-under-score", "-work-with-under-score"},
			{"/" + "a".repeat(199), boundaryPrefix, boundaryPrefix},
			{"/" + "a".repeat(200), boundaryPrefix + "-b6ymvl", boundaryPrefix + "-2e5xwc"},
			{"/" + "a".repeat(200) + "b", boundaryPrefix + "-85qkr7", boundaryPrefix + "-3604xy"},
			{"/" + "a".repeat(200) + "EJZGYRZ", boundaryPrefix + "-zik0zk", boundaryPrefix + "-u20cab"},
			{"/work/caf\u00e9-\ud83d\ude00", "-work-caf----", "-work-caf----"}
		};
	}

	/**
	 * Verifies that a run whose project path maps to a name of more than 255 characters, which a file system cannot
	 * hold as one folder name, still gets its hook-results folder.
	 *
	 * @throws IOException if the run directory cannot be created
	 */
	@Test
	public void createsHookResultsForLongPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path capture = cleanup.add(Files.createTempDirectory("claude-capture-"));
			Path directory = Files.createDirectories(capture.resolve("a".repeat(100)).resolve("b".repeat(100)).
				resolve("c".repeat(100)).resolve("runs/claude/abc123-def456"));
			HarnessRun run = new HarnessRun("claude", "abc123-def456", directory);
			Path worktree = directory.resolve("project").toAbsolutePath().normalize();
			requireThat(worktree.toString().length(), "projectPathLength").isGreaterThan(260);

			Path hookResults = ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), run);

			Path projectFolder = hookResults.resolve(ClaudeRunner.claudeProjectDirectory(worktree));
			requireThat(projectFolder.getFileName().toString().length(), "folderNameLength").isLessThanOrEqualTo(255);
			requireThat(Files.isDirectory(projectFolder), "projectFolderExists").isTrue();
		}
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
			String canonicalName = ClaudeRunner.claudeProjectDirectory(worktree);
			String parentName = ClaudeRunner.claudeProjectDirectory(capture.resolve("runs").toAbsolutePath().normalize());
			Path canonical = hookResults.resolve(canonicalName);
			Path alias = hookResults.resolve(parentName).resolve("claude/abc123-def456-project");

			requireThat(Files.isSymbolicLink(alias), "alias").isTrue();
			requireThat(Files.isSameFile(alias, canonical), "aliasTarget").isTrue();
		}
	}

	/**
	 * Verifies that a session is denied reading the runner's records of its own run, namely the run's artifacts,
	 * manifest, lock and initial project, its own settings, and the other runs and workspaces under the run root,
	 * besides the paths that the caller denies. The session can still read its project, the rest of its home, including
	 * the login that the run copied from the host, which a Claude process that a command starts reads, its Claude
	 * temporary directory and the evidence that it validates.
	 *
	 * @throws IOException if the run cannot be created
	 */
	@Test
	public void deniesReadingRunRecordsAndOtherRuns() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-run-root-"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("claude");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Files.createDirectories(directory.resolve("claude"));
			Files.writeString(Files.createDirectories(directory.resolve("home/.claude")).resolve(".credentials.json"),
				"{}");
			HarnessRun other = manager.create("claude");
			HarnessRun evidence = manager.create("claude");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path workspaces = Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
				List.of(heldOut), Map.of());

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut,
				directory.resolve("artifacts"), directory.resolve("run.json"), directory.resolve(".lock"),
				directory.resolve("initial-project"), other.directory(), evidence.directory().resolve("project"),
				evidence.directory().resolve("home"), evidence.directory().resolve("run.json"),
				evidence.directory().resolve(".lock"), workspaces, directory.resolve("home/.claude/settings.json")));
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
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path project = cleanup.add(Files.createTempDirectory(temporary, "cat-user-project-"));
			Path instructions = Files.writeString(project.resolve("CLAUDE.md"), "Follow the handbook.");
			Path documents = Files.createDirectories(project.resolve("docs"));
			Path session = Files.createDirectories(project.resolve(".cat/sessions/session-1"));
			Path otherSession = Files.createDirectories(project.resolve(".cat/sessions/session-2"));
			Path campaigns = Files.createDirectories(session.resolve("campaigns"));
			Path root = Files.createDirectories(session.resolve("optimizer-runs/claude"));
			Path heldOutRoot = Files.createDirectories(session.resolve("optimizer-runs/claude-held_out"));
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("claude");
			Path directory = run.directory();
			manager.snapshotProject(run);
			Files.createDirectories(directory.resolve("claude"));
			Files.createDirectories(directory.resolve("home"));
			HarnessRun evidence = manager.create("claude");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, run, List.of(evidenceArtifacts),
				List.of(project), Map.of());

			requireThat(deniedReads.size(), "deniedReads.size()").isEqualTo(Set.copyOf(deniedReads).size());
			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(instructions, documents,
				otherSession, campaigns, heldOutRoot, directory.resolve("artifacts"), directory.resolve("run.json"),
				directory.resolve(".lock"), directory.resolve("initial-project"),
				evidence.directory().resolve("project"), evidence.directory().resolve("home"),
				evidence.directory().resolve("run.json"), evidence.directory().resolve(".lock"),
				directory.resolve("home/.claude/settings.json")));
		}
	}

	/**
	 * The event that Claude Code writes, as its only output, when its sandbox is required but cannot start, on stock
	 * Ubuntu 24.04.
	 */
	private static final String UNAVAILABLE_EVENT = "{\"type\":\"result\",\"subtype\":\"error_during_execution\"," +
		"\"duration_ms\":0,\"is_error\":true,\"num_turns\":0,\"stop_reason\":null,\"session_id\":\"s\"," +
		"\"permission_denials\":[],\"errors\":[\"Sandbox required but unavailable: sandbox is enabled but cannot " +
		"start here: Ubuntu's AppArmor restriction on user namespaces stops bubblewrap here (\\\"loopback: Failed " +
		"RTM_NEWADDR: Operation not permitted\\\"). Add bubblewrap's AppArmor profile from " +
		"https://code.claude.com/docs/en/sandboxing, or run `sudo sysctl -w " +
		"kernel.apparmor_restrict_unprivileged_userns=0` (holds until reboot), then restart Claude Code. Set " +
		"sandbox.failIfUnavailable=false to allow unsandboxed execution.\"]," +
		"\"startup_failure_reason\":\"sandbox_required_unavailable\",\"result_index\":0}\n";
	/**
	 * What Claude Code writes to its standard error when its sandbox is required but cannot start.
	 */
	private static final String UNAVAILABLE_NOTICE = "Error: sandbox required but unavailable: sandbox is enabled " +
		"but cannot start here: Ubuntu's AppArmor restriction on user namespaces stops bubblewrap here\n" +
		"sandbox.failIfUnavailable is set — refusing to start without a working sandbox.\n";
	/**
	 * The notice that Claude Code writes when its sandbox cannot start and the session runs without it.
	 */
	private static final String DISABLED_NOTICE = "⚠ Sandbox disabled: sandbox is enabled but cannot start " +
		"here: bubblewrap is not installed\n  Commands will run WITHOUT sandboxing. Network and filesystem " +
		"restrictions will NOT be enforced.\n";
	/**
	 * The event that starts a session's output.
	 */
	private static final String INIT_EVENT = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\"}\n";
	/**
	 * The event that Claude Code writes when, with its sandbox off, it asks to approve a command that writes outside
	 * the session's working directories.
	 */
	private static final String UNCONFINED_COMMAND_EVENT = "{\"type\":\"system\",\"subtype\":\"permission_denied\"," +
		"\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\",\"message\":\"touch in '/work/O/q5.txt' needs approval. The " +
		"path is outside the working directories for this session ('/work/W'). Allowing runs the command as " +
		"written.\",\"session_id\":\"s\"}\n";
	/**
	 * The event that Claude Code writes when a deny rule denies a command, which a sandboxed session also writes.
	 */
	private static final String DENIED_COMMAND_EVENT = "{\"type\":\"system\",\"subtype\":\"permission_denied\"," +
		"\"tool_name\":\"Bash\",\"tool_use_id\":\"t2\",\"decision_reason_type\":\"subcommandResults\"," +
		"\"message\":\"Permission to use Bash with command cat /work/P/hidden.txt has been denied.\"," +
		"\"session_id\":\"s\"}\n";

	/**
	 * Verifies that a session whose Claude Code exits because its required sandbox is unavailable is refused with
	 * Claude Code's reason and a pointer to the installation guide, without Claude Code's advice to run commands
	 * without the sandbox, rather than failing as a dead process.
	 *
	 * @throws IOException if the fake process fails
	 */
	@Test
	public void refusesSessionWhoseSandboxIsUnavailable() throws IOException
	{
		try (FakeProcess process = new FakeProcess(UNAVAILABLE_EVENT, UNAVAILABLE_NOTICE, false);
			HarnessProcess session = watched(process))
		{
			requireThat(session.isAlive(), "alive").isTrue();
			try
			{
				session.sendTurn("Hello");
				throw new AssertionError("The session was not refused");
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("Ubuntu's AppArmor restriction on user namespaces").
					contains("installation guide").doesNotContain("failIfUnavailable");
			}
		}
	}

	/**
	 * Verifies that a session whose Claude Code reports that it runs without its sandbox is stopped and refused with
	 * Claude Code's reason, since its commands would otherwise run unconfined.
	 *
	 * @throws IOException if the fake process fails
	 */
	@Test
	public void stopsSessionWhoseSandboxIsDisabled() throws IOException
	{
		try (FakeProcess process = new FakeProcess(INIT_EVENT, DISABLED_NOTICE, true);
			HarnessProcess session = watched(process))
		{
			requireThat(process.destroyed, "destroyed").isTrue();
			try
			{
				session.sendTurn("Hello");
				throw new AssertionError("The session was not refused");
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("bubblewrap is not installed");
			}
		}
	}

	/**
	 * Verifies that a session whose Claude Code asks to approve a command that writes outside its working
	 * directories, which it asks only when no sandbox confines the command, is stopped and refused.
	 *
	 * @throws IOException if the fake process fails
	 */
	@Test
	public void stopsSessionWhoseCommandsRunUnconfined() throws IOException
	{
		try (FakeProcess process = new FakeProcess(INIT_EVENT + UNCONFINED_COMMAND_EVENT, "", true);
			HarnessProcess session = watched(process))
		{
			requireThat(process.destroyed, "destroyed").isTrue();
			try
			{
				session.sendTurn("Hello");
				throw new AssertionError("The session was not refused");
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("/work/O/q5.txt").contains("installation guide");
			}
		}
	}

	/**
	 * Verifies that a session whose Claude Code shows no sign of running without its sandbox, including one whose
	 * deny rule denies a command, runs its turns as usual.
	 *
	 * @throws IOException if the fake process fails
	 */
	@Test
	public void keepsSessionWithWorkingSandbox() throws IOException
	{
		try (FakeProcess process = new FakeProcess(INIT_EVENT + DENIED_COMMAND_EVENT, "Starting\n", true);
			HarnessProcess session = watched(process))
		{
			requireThat(process.destroyed, "destroyed").isFalse();
			requireThat(session.isAlive(), "alive").isTrue();
			requireThat(session.sendTurn("Hello").standardOutput(), "reply").isEqualTo("Hello");
		}
	}

	/**
	 * Returns a session that reads a process through a sandbox watch, which guards the session.
	 *
	 * @param process the session's process
	 * @return the guarded session
	 * @throws IOException if the process's output cannot be read
	 */
	private static HarnessProcess watched(Process process) throws IOException
	{
		ClaudeRunner.SandboxWatch watch = new ClaudeRunner.SandboxWatch(process, JsonMapper.builder().build());
		return watch.guard(new DrainingProcess(watch.process()));
	}

	/**
	 * A Claude Code process that has written its output and standard error.
	 */
	private static final class FakeProcess extends Process
	{
		private final byte[] output;
		private final byte[] standardError;
		private volatile boolean alive;
		private volatile boolean destroyed;

		/**
		 * Creates a process.
		 *
		 * @param output        what the process wrote to its output
		 * @param standardError what the process wrote to its standard error
		 * @param alive         {@code true} if the process is still running
		 */
		FakeProcess(String output, String standardError, boolean alive)
		{
			this.output = output.getBytes(UTF_8);
			this.standardError = standardError.getBytes(UTF_8);
			this.alive = alive;
		}

		@Override
		public OutputStream getOutputStream()
		{
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream()
		{
			return new ByteArrayInputStream(output);
		}

		@Override
		public InputStream getErrorStream()
		{
			return new ByteArrayInputStream(standardError);
		}

		@Override
		public int waitFor()
		{
			return exitValue();
		}

		@Override
		public int exitValue()
		{
			if (alive)
				throw new IllegalThreadStateException("The process is running");
			return 1;
		}

		@Override
		public void destroy()
		{
			destroyForcibly();
		}

		@Override
		public Process destroyForcibly()
		{
			destroyed = true;
			alive = false;
			return this;
		}

		@Override
		public boolean isAlive()
		{
			return alive;
		}

		@Override
		public Stream<ProcessHandle> descendants()
		{
			return Stream.empty();
		}
	}

	/**
	 * A harness process that drains its process's output and standard error when it starts, as Claude Code's process
	 * reads them, and answers each turn with its request.
	 */
	private static final class DrainingProcess implements HarnessProcess
	{
		private final Process process;

		/**
		 * Creates a harness process.
		 *
		 * @param process the process
		 * @throws IOException if the output or standard error cannot be read
		 */
		DrainingProcess(Process process) throws IOException
		{
			this.process = process;
			try (InputStream output = process.getInputStream();
				InputStream errors = process.getErrorStream())
			{
				output.transferTo(OutputStream.nullOutputStream());
				errors.transferTo(OutputStream.nullOutputStream());
			}
		}

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			return new HarnessProcessResult(0, request, "");
		}

		@Override
		public boolean isAlive()
		{
			return process.isAlive();
		}

		@Override
		public void close()
		{
			process.destroyForcibly();
		}
	}
}
