package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.installer.Integration;
import com.holdmyspot.cat.claude.layout.ClaudeInstallation;
import com.holdmyspot.cat.claude.runner.ClaudeAuthentication;
import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.runner.ClaudeProcess;
import com.holdmyspot.cat.claude.runner.ClaudeProcessEnvironment;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.claude.runner.ClaudeSessionRole;
import com.holdmyspot.cat.claude.runner.ClaudeSessionSandbox;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestBroker;
import com.holdmyspot.cat.common.runner.SessionSlots;
import com.holdmyspot.cat.common.runner.SetupCommands;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.SeatbeltProfile;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link ClaudeRunner}.
 */
public final class ClaudeRunnerTest
{
	/**
	 * Reads and writes the fixtures' settings and install records.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The variables that select a Claude credential or the login store that Claude Code reads, which only the source
	 * home's files decide for a session.
	 */
	private static final List<String> CREDENTIAL_VARIABLES = List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_SECURESTORAGE_CONFIG_DIR");
	/**
	 * The token of the CAT credential file of a source whose settings also name a credential.
	 */
	private static final String FILE_TOKEN = SourceHomes.SETUP_TOKEN + "-file";
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
	 * Verifies the Claude launcher creates its Bubblewrap launch configuration beside the session-owned Claude
	 * temporary directory so nested-session cleanup removes the workaround with the completed nested session.
	 */
	@Test
	public void resolvesSessionLaunchConfiguration()
	{
		Path temporaryDirectory = Path.of("/system-temporary-directory/.cat/claude/claude/session-id/claude").
			toAbsolutePath();

		requireThat(ClaudeRunner.launchConfigurationPath(temporaryDirectory), "launchConfiguration").isEqualTo(
			Path.of("/system-temporary-directory/.cat/claude/claude/session-id/.claude/launch.json").toAbsolutePath());
	}

	/**
	 * Verifies that the Claude process environment marks the session as a nested session, even when the caller's settings
	 * file sets the marker's variable otherwise, so that the session's commands send their sessions to the nested
	 * session's runner.
	 *
	 * @throws IOException if the environment cannot be built
	 */
	@Test
	public void marksNestedSession() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-session-marker-"));
			Path settings = Files.writeString(home.resolve("settings.json"), """
				{"env": {"%s": ""}}
				""".formatted(HarnessEnvironment.NESTED_SESSION_VARIABLE));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, settings, home, null, SourceHomes.keyHelper(home));

			requireThat(result.get(HarnessEnvironment.NESTED_SESSION_VARIABLE), "marker").isEqualTo("1");
		}
	}

	/**
	 * Verifies that a Claude child needs the shared dependency caches of the caller's home folder, which it writes.
	 *
	 * @throws IOException if the fixture or the report cannot be built
	 */
	@Test
	public void childNeedsSharedCaches() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-child-needs-")).toRealPath();
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString());

			ChildNeedsReport needs = ClaudeRunner.childNeeds(scope, environment, SourceHomes.keyHelper(home));

			requireThat(needs.writes(), "writes").isEqualTo(List.of(home.resolve(".npm"), home.resolve(".m2")));
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
				HarnessDependencyCaches.NONE, home.resolve("missing-settings.json"), home, null, SourceHomes.keyHelper(home));

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
				HarnessDependencyCaches.NONE, settings, home, null, SourceHomes.keyHelper(home));

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
				HarnessDependencyCaches.NONE, settings, home, null, SourceHomes.keyHelper(home));

			requireThat(result.keySet(), "variables").doesNotContainAny(SUBAGENT_VARIABLES).
				doesNotContain("CLAUDE_CODE_EFFORT_LEVEL").doesNotContain("CLAUDE_CODE_DISABLE_TERMINAL_TITLE");
		}
	}

	/**
	 * Verifies that the commands of a session cannot read the user's Claude login file, in {@code .claude} in the
	 * user's home directory, in the directory that {@code CLAUDE_CONFIG_DIR} names or in the one that
	 * {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names, although the session's Claude Code uses the login there: the
	 * session's sandbox policy denies reading each file and lets the commands neither write nor read again any
	 * directory that holds them.
	 *
	 * @throws IOException if the nested session or its settings cannot be created
	 */
	@Test
	public void deniesUserLoginToSessionCommands() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-user-login-")).
				toRealPath();
			Path userHome = Files.createDirectory(root.resolve("user"));
			Path defaultLogin = Files.writeString(Files.createDirectory(userHome.resolve(".claude")).
				resolve(".credentials.json"), "{}");
			Path configured = Files.createDirectory(root.resolve("claude-config"));
			Path configuredLogin = Files.writeString(configured.resolve(".credentials.json"), "{}");
			Path store = Files.createDirectory(root.resolve("login-store"));
			Path storeLogin = Files.writeString(store.resolve(".credentials.json"), "{}");
			Map<String, String> environment = Map.of(Platform.current().homeVariable(), userHome.toString(),
				"CLAUDE_CONFIG_DIR", configured.toString(), "CLAUDE_SECURESTORAGE_CONFIG_DIR", store.toString());
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			manager.snapshotProject(nestedSession);
			Path project = Files.createDirectories(nestedSession.directory().resolve("project/.git")).getParent();
			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, nestedSession, List.of(), List.of(),
				environment);

			ChildPolicy policy = ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"),
				nestedSession.directory().resolve("home/.claude/settings.json"), nestedSession.directory().resolve("claude"),
				nestedSession.directory().resolve("home"), List.of(), nestedSession.directory(), deniedReads, "", null, project,
				nestedSession.directory(), null, ExtraPermissions.NONE, "api.example");

			List<Path> logins = List.of(defaultLogin, configuredLogin, storeLogin);
			requireThat(policy.readDenials(), "readDenials").containsAll(logins);
			List<Path> grants = new ArrayList<>(policy.writeRoots());
			grants.addAll(policy.readReallowances());
			for (Path login : logins)
			{
				for (Path grant : grants)
					requireThat(login.startsWith(grant), login + ".startsWith(" + grant + ")").isFalse();
			}
		}
	}

	/**
	 * Verifies that every nested session keeps its Claude Code session, whether or not it copies the session's
	 * transcripts or may request sessions, so that the nested session's harness session can continue after its runner
	 * ends, and that the command sets no generated sandbox policy of its own.
	 */
	@Test
	public void persistsEverySession()
	{
		// Requirement: R-I7
		List<List<String>> commands = List.of(
			ClaudeRunner.command("claude", "sonnet", "low", "", Path.of("marketplace"), false, Path.of("hook-results"),
				List.of()),
			ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"), List.of()));
		for (List<String> command : commands)
			requireThat(command, "command").doesNotContain("--no-session-persistence").doesNotContain("--settings");
	}

	/**
	 * Verifies that a runner that continues a nested session's harness session asks Claude Code to resume that session,
	 * which Claude Code keeps under the same identifier.
	 */
	@Test
	public void resumesRecordedSession()
	{
		List<String> command = ClaudeRunner.resumed(ClaudeRunner.command("claude", "sonnet", "low", "", List.of(),
			Path.of("hook-results"), List.of()), "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");

		int option = command.indexOf("--resume");
		requireThat(option, "option").isGreaterThan(-1);
		requireThat(command.get(option + 1), "session").isEqualTo("5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90");
		requireThat(command, "command").doesNotContain("--fork-session");
	}

	/**
	 * Verifies that the session runs in the {@code acceptEdits} permission mode, in which the sandbox confines its
	 * commands and its edits stay inside the project.
	 */
	@Test
	public void selectsAcceptEditsPermissionMode()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of());

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
			List.of());
		List<String> low = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of());

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
	 * Verifies that a session that may request sessions gets the same system prompt as one that may request none: the
	 * command adds no instructions on requesting a session, which the session reads in CAT's rules instead.
	 */
	@Test
	public void addsNoRequestInstructions()
	{
		// Requirement: R-I9
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of());

		requireThat(command, "command").doesNotContain("--append-system-prompt").
			doesNotContain("--append-system-prompt-file").doesNotContain("--system-prompt").
			doesNotContain("--system-prompt-file");
	}

	/**
	 * Verifies a nested session grants Claude access to spilled hook context without exposing the isolated home.
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
	 * Verifies a nested session with a trial plugin grants Claude access to that plugin and to spilled hook context.
	 */
	// Requirement: R-W12
	@Test
	public void permitsTrialPluginDirectory()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "",
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

			List<Path> installed = install(plugin(root, "cat-artifact", "cat"), false,
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
			requireThat(Files.notExists(configuration.resolve("settings.json")), "settings").isTrue();
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

			List<Path> installed = install(plugin(root, "cat-artifact", "cat"), false,
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
			requireThat(install(artifact, false, List.of(), withoutCat, (_, step) -> steps.add(step)), "withoutCat").
				isEmpty();
			requireThat(steps, "withoutCatSteps").isEmpty();
			requireThat(Files.notExists(withoutCat), "withoutCatConfiguration").isTrue();

			Path withCat = root.resolve("with/.claude");
			List<Path> installed = install(artifact, true, List.of(), withCat, (_, step) -> steps.add(step));
			Path marker = new ClaudeInstallation(withCat).runnerMarker();
			requireThat(marker, "runnerMarker").isRegularFile();
			requireThat(Files.exists(new ClaudeInstallation(withCat).policy()), "policy").isFalse();
			requireThat(Files.exists(new ClaudeInstallation(withoutCat).runnerMarker()), "markerWithoutCat").isFalse();
			requireThat(installed, "withCat").isEqualTo(List.of(withCat.resolve("plugins/cat-marketplace/plugins/cat")));
			requireThat(steps, "withCatSteps").isEqualTo(List.of("marketplace-add", "plugin-install"));
		}
	}

	/**
	 * Verifies that installing CAT adds to the isolated settings the entries that CAT's installer writes for the
	 * installed plugin, beside the settings that are already there.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsCatSettingsEntries() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-settings-"));
			Path configuration = Files.createDirectories(root.resolve("home/.claude"));
			Files.writeString(configuration.resolve("settings.json"), """
				{"model": "opus", "permissions": {"allow": ["Skill"]}}
				""");

			List<Path> installed = install(plugin(root, "cat-artifact", "cat"), true, List.of(), configuration,
				(_, _) ->
				{
				});

			Path installer = installed.get(0).resolve(PluginDirectory.INSTALLER);
			Map<String, List<String>> entries = new Integration().permissions(Map.of(), installer);
			JsonNode settings = JSON.readTree(Files.readString(configuration.resolve("settings.json")));
			requireThat(settings.path("model").asString(), "model").isEqualTo("opus");
			List<String> excluded = strings(settings.path("sandbox").path("excludedCommands"));
			requireThat(excluded, "excluded").isEqualTo(entries.get("settings.json#sandbox.excludedCommands"));
			List<String> allowed = new ArrayList<>(List.of("Skill"));
			allowed.addAll(entries.get("settings.json#permissions.allow"));
			requireThat(strings(settings.path("permissions").path("allow")), "allowed").isEqualTo(allowed);
			if (!Platform.current().isWindows())
			{
				String server = installer.resolveSibling("request-server").toString();
				requireThat(excluded, "excluded").contains(server);
				requireThat(allowed, "allowed").contains("Bash(" + server + ")");
			}
		}
	}

	/**
	 * Verifies that installing CAT adds the installer's entries for each copy of CAT that Claude Code's install
	 * records name, beside the copy in the nested session's marketplace.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsEntriesForRecordedCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-settings-"));
			Path configuration = root.resolve("home/.claude");
			Path recorded = plugin(root, "cache/cat/cat/1.0-a", "cat");

			List<Path> installed = install(plugin(root, "cat-artifact", "cat"), true, List.of(), configuration,
				(_, step) ->
				{
					if (step.equals("plugin-install"))
					{
						Files.createDirectories(configuration.resolve("plugins"));
						Files.writeString(configuration.resolve("plugins/installed_plugins.json"), JSON.writeValueAsString(
							Map.of("version", 2, "plugins", Map.of("cat@cat", List.of(Map.of("scope", "user",
								"installPath", recorded.toString()))))));
					}
				});

			Integration integration = new Integration();
			JsonNode settings = JSON.readTree(Files.readString(configuration.resolve("settings.json")));
			List<String> excluded = strings(settings.path("sandbox").path("excludedCommands"));
			List<String> expected = new ArrayList<>();
			for (Path plugin : List.of(installed.get(0), recorded))
				expected.addAll(integration.permissions(Map.of(), plugin.resolve(PluginDirectory.INSTALLER)).
					get("settings.json#sandbox.excludedCommands"));
			requireThat(excluded, "excluded").isEqualTo(expected);
		}
	}

	/**
	 * Installs plugins into an isolated Claude configuration with a file replacer that yields instead of sleeping.
	 *
	 * @param artifact the CAT artifact
	 * @param installCat {@code true} to install CAT
	 * @param trialPlugins the trial plugins
	 * @param configuration the isolated Claude configuration directory
	 * @param commands runs the setup commands
	 * @return the directory of each installed plugin
	 * @throws IOException if the installation fails
	 */
	private static List<Path> install(Path artifact, boolean installCat, List<Path> trialPlugins, Path configuration,
		SetupCommands commands) throws IOException
	{
		return ClaudeRunner.installPlugins("claude", artifact, installCat, trialPlugins, configuration, Map.of(),
			Yielding.fileReplacer(), JSON, commands);
	}

	/**
	 * Returns the text of each element of a JSON array.
	 *
	 * @param array the array
	 * @return the elements' text, in order
	 */
	private static List<String> strings(JsonNode array)
	{
		List<String> strings = new ArrayList<>();
		for (JsonNode element : array)
			strings.add(element.asString());
		return strings;
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
	 * Verifies that a nested session whose project path maps to a name of more than 255 characters, which a file system
	 * cannot hold as one folder name, still gets its hook-results folder.
	 *
	 * @throws IOException if the session folder cannot be created
	 */
	@Test
	public void createsHookResultsForLongPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path capture = cleanup.add(Files.createTempDirectory("claude-capture-"));
			Path directory = Files.createDirectories(capture.resolve("a".repeat(100)).resolve("b".repeat(100)).
				resolve("c".repeat(100)).resolve("results/claude/abc123-def456"));
			NestedSession nestedSession = new NestedSession("claude", "abc123-def456", directory);
			Path worktree = directory.resolve("project").toAbsolutePath().normalize();
			requireThat(worktree.toString().length(), "projectPathLength").isGreaterThan(260);

			Path hookResults = ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), nestedSession);

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
			Path directory = Files.createDirectories(capture.resolve("results/claude/abc123-def456"));
			NestedSession nestedSession = new NestedSession("claude", "abc123-def456", directory);
			Path hookResults = ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), nestedSession);
			Path worktree = directory.resolve("project").toAbsolutePath().normalize();
			String canonicalName = ClaudeRunner.claudeProjectDirectory(worktree);
			String parentName = ClaudeRunner.claudeProjectDirectory(capture.resolve("results").toAbsolutePath().normalize());
			Path canonical = hookResults.resolve(canonicalName);
			Path alias = hookResults.resolve(parentName).resolve("claude/abc123-def456-project");

			requireThat(Files.isSymbolicLink(alias), "alias").isTrue();
			requireThat(Files.isSameFile(alias, canonical), "aliasTarget").isTrue();
		}
	}

	/**
	 * Verifies a resumed nested session starts against the spill-context alias that its first start created: the
	 * resumed start neither fails nor changes the alias.
	 *
	 * @throws IOException if the session folder cannot be created
	 */
	@Test
	public void resumedSessionKeepsSpillContextAlias() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path capture = cleanup.add(Files.createTempDirectory("claude-capture-"));
			Path directory = Files.createDirectories(capture.resolve("results/claude/abc123-def456"));
			NestedSession nestedSession = new NestedSession("claude", "abc123-def456", directory);
			Path hookResults = ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), nestedSession);
			String parentName = ClaudeRunner.claudeProjectDirectory(capture.resolve("results").toAbsolutePath().normalize());
			Path alias = hookResults.resolve(parentName).resolve("claude/abc123-def456-project");
			Path target = Files.readSymbolicLink(alias);

			Path resumedHookResults = ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), nestedSession);

			requireThat(resumedHookResults, "resumedHookResults").isEqualTo(hookResults);
			requireThat(Files.readSymbolicLink(alias), "aliasTarget").isEqualTo(target);
		}
	}

	/**
	 * Verifies that a start refuses a spill-context alias that another session left in its place, so that the
	 * session cannot read that session's hook results.
	 *
	 * @throws IOException if the session folder cannot be created
	 */
	@Test(expectedExceptions = IOException.class)
	public void refusesForeignSpillContextAlias() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path capture = cleanup.add(Files.createTempDirectory("claude-capture-"));
			Path directory = Files.createDirectories(capture.resolve("results/claude/abc123-def456"));
			NestedSession nestedSession = new NestedSession("claude", "abc123-def456", directory);
			Path hookResults = ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), nestedSession);
			String parentName = ClaudeRunner.claudeProjectDirectory(capture.resolve("results").toAbsolutePath().normalize());
			Path alias = hookResults.resolve(parentName).resolve("claude/abc123-def456-project");
			Files.delete(alias);
			Files.createSymbolicLink(alias, Path.of("elsewhere"));

			ClaudeRunner.createHookResultsDirectory(directory.resolve("home"), nestedSession);
		}
	}

	/**
	 * Verifies that a session is denied reading the runner's records of its own nested session, namely the nested
	 * session's artifacts, manifest, lock and initial project, its own settings, and the other nested sessions and
	 * workspaces under the results folder, besides the paths that the caller denies, and any login file in its home. The
	 * session can still read its project, the rest of its home, its Claude temporary directory and the evidence that it
	 * validates.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void deniesReadingRecordsAndOtherSessions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			Path directory = nestedSession.directory();
			manager.snapshotProject(nestedSession);
			Files.createDirectories(directory.resolve("claude"));
			Files.writeString(Files.createDirectories(directory.resolve("home/.claude")).resolve(".credentials.json"),
				"{}");
			NestedSession other = manager.create("claude");
			NestedSession evidence = manager.create("claude");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path workspaces = Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, nestedSession, List.of(evidenceArtifacts),
				List.of(heldOut), Map.of());

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut,
				directory.resolve("artifacts"), directory.resolve("session.json"), directory.resolve(".lock"),
				directory.resolve("initial-project"), other.directory(), evidence.directory().resolve("project"),
				evidence.directory().resolve("home"), evidence.directory().resolve("session.json"),
				evidence.directory().resolve(".lock"), workspaces, directory.resolve("home/.claude/settings.json"),
				directory.resolve("home/.claude/cat-api-key"), directory.resolve("home/.claude/.credentials.json")));
		}
	}

	/**
	 * Verifies that a session whose results folder lies inside a project that the caller denies is denied every file
	 * and directory of that project except the directories that lead to its nested session and evidence, each path once.
	 * The session can still read its project, its home, its Claude temporary directory and the evidence that it
	 * validates, but not the files of the project around them.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void deniesProjectAroundResultsFolder() throws IOException
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
			Path root = Files.createDirectories(session.resolve("optimizer-nested-sessions/claude"));
			Path heldOutRoot = Files.createDirectories(session.resolve("optimizer-nested-sessions/claude-held_out"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			Path directory = nestedSession.directory();
			manager.snapshotProject(nestedSession);
			Files.createDirectories(directory.resolve("claude"));
			Files.createDirectories(directory.resolve("home"));
			NestedSession evidence = manager.create("claude");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, nestedSession, List.of(evidenceArtifacts),
				List.of(project), Map.of());

			requireThat(deniedReads.size(), "deniedReads.size()").isEqualTo(Set.copyOf(deniedReads).size());
			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(instructions, documents,
				otherSession, campaigns, heldOutRoot, directory.resolve("artifacts"), directory.resolve("session.json"),
				directory.resolve(".lock"), directory.resolve("initial-project"),
				evidence.directory().resolve("project"), evidence.directory().resolve("home"),
				evidence.directory().resolve("session.json"), evidence.directory().resolve(".lock"),
				directory.resolve("home/.claude/settings.json"), directory.resolve("home/.claude/cat-api-key")));
		}
	}

	/**
	 * Verifies that a session that its requester's sandbox policy bounds starts although its nested session lies in the
	 * requester's slots directory, which that policy does not let the requester write: the first requested
	 * session's policy, which holds only its home, requests directory, worktree registrations and dependency cache,
	 * bounds the session that it requests, and the settings of that session let its commands write its own home, the
	 * cache, its requests directory and its worktree registrations, but neither its whole session folder nor its
	 * responses directory.
	 *
	 * @throws IOException if the nested sessions cannot be created
	 */
	@Test
	public void startsNestedRequestedSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-nested-session-")).toRealPath();
			Path artifact = root.resolve("artifact");
			Path cache = Files.createDirectories(root.resolve("cache"));
			NestedSessionManager firstManager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession first = firstManager.create("claude");
			SessionRequestAccess firstAccess = SessionRequestAccess.allowing(first, artifact, 1, Platform.current(),
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			Path firstProject = Files.createDirectories(first.directory().resolve("project/.git")).getParent();
			ChildPolicy firstPolicy = ClaudeConnectionSettings.writeIsolatedSettings(scope,
				root.resolve("missing.json"), first.directory().resolve("home/.claude/settings.json"),
				first.directory().resolve("claude"), first.directory().resolve("home"), List.of(cache),
				first.directory(), List.of(), "", firstAccess, firstProject, first.directory(), null,
				ExtraPermissions.NONE, "api.example");
			NestedSessionManager nestedManager = new NestedSessionManager(jvm.fileLocks(),
				firstAccess.requestedResultsFolder(1), Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nested = nestedManager.create("claude");
			SessionRequestAccess nestedAccess = SessionRequestAccess.allowing(nested, artifact, 0, Platform.current(),
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			Path nestedProject = Files.createDirectories(nested.directory().resolve("project/.git")).getParent();
			Path nestedSettings = nested.directory().resolve("home/.claude/settings.json");

			ChildPolicy nestedPolicy = ClaudeConnectionSettings.writeIsolatedSettings(scope,
				root.resolve("missing.json"), nestedSettings, nested.directory().resolve("claude"),
				nested.directory().resolve("home"), List.of(cache), nested.directory(), List.of(), "", nestedAccess,
				nestedProject, nested.directory(), firstPolicy, ExtraPermissions.NONE, "api.example");

			JsonNode sandbox = scope.json().readTree(Files.readString(nestedSettings)).path("sandbox");
			List<String> allowWrite = new ArrayList<>();
			for (JsonNode path : sandbox.path("filesystem").path("allowWrite"))
				allowWrite.add(path.asString());
			requireThat(allowWrite, "allowWrite").isEqualTo(List.of(nested.directory().resolve("home").toString(),
				cache.toString(), nestedAccess.requests().toString()));
			requireThat(nestedPolicy.writeRoots(), "writeRoots").doesNotContain(nested.directory());
		}
	}

	/**
	 * Verifies that a session that a Claude Code session requests may read, but not write, the CAT plugin that the
	 * requesting session's commands run, such as a launcher that the request's prompt names, while its settings still
	 * deny it the rest of the requesting nested session, such as that session's login.
	 *
	 * @throws IOException if the nested sessions cannot be created
	 */
	@Test
	public void readsPluginOfRequestingSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-requester-plugin-")).
				toRealPath();
			Path artifact = root.resolve("artifact");
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path results = root.resolve("results");
			NestedSessionManager firstManager = new NestedSessionManager(jvm.fileLocks(), results, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession first = firstManager.create("claude");
			SessionRequestAccess firstAccess = SessionRequestAccess.allowing(first, artifact, 1, Platform.current(),
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			Path firstProject = Files.createDirectories(first.directory().resolve("project/.git")).getParent();
			Path firstHome = first.directory().resolve("home");
			Path plugin = firstHome.resolve(".claude/plugins/cat-marketplace/plugins/cat");
			Path launcher = Files.createDirectories(plugin.resolve("bin")).resolve("prompt-optimizer");
			Files.writeString(launcher, "launcher");
			Path login = firstHome.resolve(".claude/.credentials.json");
			Files.writeString(login, "login");
			ChildPolicy firstPolicy = ClaudeConnectionSettings.writeIsolatedSettings(scope,
				root.resolve("missing.json"), firstHome.resolve(".claude/settings.json"),
				first.directory().resolve("claude"), firstHome, List.of(cache), first.directory(), List.of(), "",
				firstAccess, firstProject, first.directory(), null, ExtraPermissions.NONE, "api.example");
			NestedSessionManager nestedManager = new NestedSessionManager(jvm.fileLocks(),
				firstAccess.requestedResultsFolder(1), Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nested = nestedManager.create("claude");
			SessionRequestAccess nestedAccess = SessionRequestAccess.allowing(nested, artifact, 0, Platform.current(),
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			Path nestedProject = Files.createDirectories(nested.directory().resolve("project/.git")).getParent();
			Path nestedSettings = nested.directory().resolve("home/.claude/settings.json");

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(nestedManager, nested, List.of(),
				SessionRequestBroker.resultsDenials(results, plugin), Map.of(), nestedAccess);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), nestedSettings,
				nested.directory().resolve("claude"), nested.directory().resolve("home"), List.of(cache),
				nested.directory(), deniedReads, "", nestedAccess, nestedProject, nested.directory(), firstPolicy,
				ExtraPermissions.NONE, "api.example");

			JsonNode written = scope.json().readTree(Files.readString(nestedSettings));
			List<Path> denyRead = paths(written.path("sandbox").path("filesystem").path("denyRead"));
			List<Path> allowWrite = paths(written.path("sandbox").path("filesystem").path("allowWrite"));
			List<String> rules = new ArrayList<>();
			for (JsonNode rule : written.path("permissions").path("deny"))
				rules.add(rule.asString());
			for (Path path = launcher; path != null; path = path.getParent())
			{
				requireThat(denyRead, "denyRead").doesNotContain(path);
				requireThat(allowWrite, "allowWrite").doesNotContain(path);
				requireThat(rules, "rules").doesNotContain(readRule(path, "/**")).doesNotContain(readRule(path, ""));
			}
			requireThat(denyRead.stream().anyMatch(login::startsWith), "loginDenied").isTrue();
			boolean loginRule = false;
			for (Path path = login; path != null; path = path.getParent())
			{
				if (rules.contains(readRule(path, "/**")) || rules.contains(readRule(path, "")))
					loginRule = true;
			}
			requireThat(loginRule, "loginRule").isTrue();
		}
	}

	/**
	 * Returns the paths of a list of settings.
	 *
	 * @param list the list
	 * @return its paths
	 */
	private static List<Path> paths(JsonNode list)
	{
		List<Path> paths = new ArrayList<>();
		for (JsonNode path : list)
			paths.add(Path.of(path.asString()));
		return paths;
	}

	/**
	 * Returns the permission rule that denies reading a path whose names hold no character that a rule escapes.
	 *
	 * @param path        an absolute path
	 * @param descendants {@code "/**"} to name the path and its contents, or {@code ""} to name the path alone
	 * @return the rule
	 */
	private static String readRule(Path path, String descendants)
	{
		return "Read(/" + ClaudeConnectionSettings.rulePath(path) + descendants + ")";
	}

	/**
	 * Verifies that a requested session of a nested session that may request hundreds of sessions is denied the nested
	 * sessions of all the others and the requester's own files with a number of denied paths that is a small fraction of
	 * the number of sessions, so that its settings stay within what Claude Code can sandbox its commands with, and that
	 * the session's own project stays readable.
	 *
	 * @throws IOException if the nested sessions cannot be created
	 */
	@Test
	public void deniesSiblingsWithFewPaths() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-many-requests-")).
				toRealPath();
			Path artifact = root.resolve("artifact");
			Path cache = Files.createDirectories(root.resolve("cache"));
			int sessionRequests = 500;
			int ordinal = 358;
			NestedSessionManager firstManager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession first = firstManager.create("claude");
			SessionRequestAccess firstAccess = SessionRequestAccess.allowing(first, artifact, sessionRequests,
				Platform.current(), SessionRequestAccess.Shell.POSIX).orElseThrow();
			NestedSessionManager nestedManager = new NestedSessionManager(jvm.fileLocks(),
				firstAccess.requestedResultsFolder(ordinal), Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nested = nestedManager.create("claude");
			SessionRequestAccess nestedAccess = SessionRequestAccess.allowing(nested, artifact, 0, Platform.current(),
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			Path nestedProject = Files.createDirectories(nested.directory().resolve("project/.git")).getParent();
			Files.createDirectories(nested.directory().resolve("claude"));
			Files.createDirectories(nested.directory().resolve("home"));
			Path settings = nested.directory().resolve("home/.claude/settings.json");

			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(nestedManager, nested, List.of(),
				List.of(first.directory()), Map.of(), nestedAccess);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), settings,
				nested.directory().resolve("claude"), nested.directory().resolve("home"), List.of(cache),
				nested.directory(), deniedReads, "", nestedAccess, nestedProject, nested.directory(), null,
				ExtraPermissions.NONE, "api.example");

			JsonNode written = scope.json().readTree(Files.readString(settings));
			requireThat(written.path("sandbox").path("filesystem").path("denyRead").size(), "denyRead.size()").
				isLessThan(250);
			requireThat(written.path("permissions").path("deny").size(), "deny.size()").isLessThan(250);
			for (int other = 1; other <= sessionRequests; ++other)
			{
				if (other == ordinal)
					continue;
				Path slot = firstAccess.requestedResultsFolder(other);
				requireThat(deniedReads.stream().anyMatch(slot::startsWith), "slotDenied").withContext(other, "ordinal").
					isTrue();
			}
			requireThat(deniedReads.stream().anyMatch(nestedProject::startsWith), "ownProjectDenied").isFalse();
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
		"\"startup_failure_reason\":\"sandbox_required_unavailable\",\"result_index\":0}";
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
	 * The event that starts a turn's output: Claude Code reports that its session is running.
	 */
	private static final String RUNNING_EVENT = "{\"type\":\"system\",\"subtype\":\"session_state_changed\"," +
		"\"state\":\"running\",\"session_id\":\"s\"}";
	/**
	 * The event that follows {@link #RUNNING_EVENT} at the start of a turn.
	 */
	private static final String INIT_EVENT = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\"}";
	/**
	 * The event that Claude Code writes when, with its sandbox off, it asks to approve a command that writes outside
	 * the session's working directories.
	 */
	private static final String UNCONFINED_COMMAND_EVENT = "{\"type\":\"system\",\"subtype\":\"permission_denied\"," +
		"\"tool_name\":\"Bash\",\"tool_use_id\":\"t1\",\"message\":\"touch in '/work/O/q5.txt' needs approval. The " +
		"path is outside the working directories for this session ('/work/W'). Allowing runs the command as " +
		"written.\",\"session_id\":\"s\"}";
	/**
	 * The event that Claude Code writes when a deny rule denies a command, which a sandboxed session also writes.
	 */
	private static final String DENIED_COMMAND_EVENT = "{\"type\":\"system\",\"subtype\":\"permission_denied\"," +
		"\"tool_name\":\"Bash\",\"tool_use_id\":\"t2\",\"decision_reason_type\":\"subcommandResults\"," +
		"\"message\":\"Permission to use Bash with command cat /work/P/hidden.txt has been denied.\"," +
		"\"session_id\":\"s\"}";

	/**
	 * Verifies that a session whose Claude Code exits because its required sandbox is unavailable is refused with
	 * Claude Code's reason and a pointer to the installation guide, without Claude Code's advice to run commands
	 * without the sandbox, rather than failing as a dead process.
	 *
	 * @throws IOException if the session fails other than by its refusal
	 */
	@Test
	public void refusesSessionWhoseSandboxIsUnavailable() throws IOException
	{
		try (HarnessProcess session = watched(endedAtStartup(UNAVAILABLE_EVENT, UNAVAILABLE_NOTICE)))
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
	 * @throws IOException if the session fails other than by its refusal
	 */
	@Test(timeOut = 10_000)
	public void stopsSessionWhoseSandboxIsDisabled() throws IOException
	{
		// Claude Code writes the notice at start-up; its turn prints nothing until the watch stops it
		FakeHarnessCli claude = answering(DISABLED_NOTICE);
		try (HarnessProcess session = watched(claude))
		{
			try
			{
				session.sendTurn("Hello");
				throw new AssertionError("The session was not refused");
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("bubblewrap is not installed");
			}
			requireThat(claude.isAlive(), "claudeAlive").isFalse();
		}
	}

	/**
	 * Verifies that a session whose Claude Code asks to approve a command that writes outside its working
	 * directories, which it asks only when no sandbox confines the command, is stopped and refused.
	 *
	 * @throws IOException if the session fails other than by its refusal
	 */
	@Test(timeOut = 10_000)
	public void stopsSessionWhoseCommandsRunUnconfined() throws IOException
	{
		FakeHarnessCli claude = answering("", RUNNING_EVENT, INIT_EVENT, UNCONFINED_COMMAND_EVENT);
		try (HarnessProcess session = watched(claude))
		{
			try
			{
				session.sendTurn("Hello");
				throw new AssertionError("The session was not refused");
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains("/work/O/q5.txt").contains("installation guide");
			}
			requireThat(claude.isAlive(), "claudeAlive").isFalse();
		}
	}

	/**
	 * Verifies that a session whose Claude Code shows no sign of running without its sandbox, including one whose
	 * deny rule denies a command, runs its turns as usual.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void keepsSessionWithWorkingSandbox() throws IOException
	{
		FakeHarnessCli claude = answering("Starting\n", RUNNING_EVENT, INIT_EVENT, DENIED_COMMAND_EVENT,
			"{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\"," +
				"\"text\":\"Hello back\"}]},\"parent_tool_use_id\":null,\"session_id\":\"s\"}",
			"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"Hello back\"," +
				"\"session_id\":\"s\"}",
			"{\"type\":\"system\",\"subtype\":\"session_state_changed\",\"state\":\"idle\",\"session_id\":\"s\"}");
		try (HarnessProcess session = watched(claude))
		{
			HarnessProcessResult result = session.sendTurn("Hello");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("Hello back");
			requireThat(session.isAlive(), "alive").isTrue();
			requireThat(claude.isAlive(), "claudeAlive").isTrue();
		}
	}

	/**
	 * Returns a Claude Code stand-in that has written its output and standard error and exited with status 1 at
	 * start-up, before it read a message.
	 *
	 * @param event         what it wrote on its output
	 * @param standardError what it wrote on its standard error
	 * @return the stand-in
	 */
	private static FakeHarnessCli endedAtStartup(String event, String standardError)
	{
		FakeHarnessCli claude = new FakeHarnessCli(_ ->
		{
		}, 1);
		claude.emit(event);
		claude.printError(standardError);
		claude.exit();
		return claude;
	}

	/**
	 * Returns a Claude Code stand-in that writes text on its standard error at start-up and answers each user message
	 * with the same lines.
	 *
	 * @param standardError what it writes on its standard error
	 * @param lines         the lines, in the shape of those that Claude Code 2.1.286 printed in the timelines under
	 *                      {@code runner/turn-boundary}
	 * @return the stand-in
	 */
	private static FakeHarnessCli answering(String standardError, String... lines)
	{
		AtomicReference<FakeHarnessCli> claude = new AtomicReference<>();
		claude.set(new FakeHarnessCli(request ->
		{
			if (request.contains("\"type\":\"user\""))
				claude.get().emit(lines);
		}));
		claude.get().printError(standardError);
		return claude.get();
	}

	/**
	 * Returns a session that reads Claude Code through a sandbox watch, which guards the session.
	 *
	 * @param claude Claude Code's process
	 * @return the guarded session
	 */
	private static HarnessProcess watched(Process claude)
	{
		ClaudeRunner.SandboxWatch watch = new ClaudeRunner.SandboxWatch(claude, JsonMapper.builder().build());
		return watch.guard(new ClaudeProcess(new TestHarnessRunnerScope(), watch.process()));
	}

	/**
	 * Verifies that the session's Claude process receives none of the values that the runner's environment and the
	 * runner's own settings file give the credential variables and the login store's variable, nor the runner's request
	 * to scrub its commands' environment, so that only the source home's files decide the session's credential, while
	 * the runner's subagent settings still reach it.
	 *
	 * @throws IOException if the fixture or the environment cannot be built
	 */
	@Test
	public void sessionTakesNoRunnerCredential() throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-source-environment-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.settings(source, """
				{"apiKeyHelper": "printf key"}
				""");
			Path runnerHome = SourceHomes.create(root, "runner");
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");
			environment.put(Platform.current().homeVariable(), runnerHome.toString());
			environment.put("CLAUDE_CODE_SUBAGENT_MODEL", "haiku");
			// No session asks Claude Code to scrub its commands' environment, so the runner's own request stays out
			environment.put("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB", "1");
			ObjectNode settingsEnv = JSON.createObjectNode();
			for (String variable : CREDENTIAL_VARIABLES)
			{
				environment.put(variable, "runner-" + variable);
				settingsEnv.put(variable, "settings-" + variable);
			}
			Path runnerSettings = Files.writeString(runnerHome.resolve(".claude/settings.json"),
				JSON.writeValueAsString(JSON.createObjectNode().set("env", settingsEnv)));
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, null), environment,
				Platform.current(), SourceHomes.PLUGIN);

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, runnerSettings, root.resolve("session-home"), null, authentication);

			for (String variable : CREDENTIAL_VARIABLES)
			{
				requireThat(result.values(), "values").doesNotContain("runner-" + variable).
					doesNotContain("settings-" + variable);
			}
			requireThat(result.keySet(), "variables").doesNotContain("CLAUDE_SECURESTORAGE_CONFIG_DIR");
			requireThat(result.get("CLAUDE_CODE_SUBAGENT_MODEL"), "subagentModel").isEqualTo("haiku");
			requireThat(result.keySet(), "variables").doesNotContain("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB");
		}
	}

	/**
	 * Verifies that a token in the source's settings never reaches the session's Claude process, which therefore holds
	 * no token variable, no request to scrub its commands' environment and no fixture token as any variable's value,
	 * and that the process states the token's class of login.
	 *
	 * @param variable the token's variable
	 * @param expected the class that {@code CAT_HOST_LOGIN} states
	 * @throws IOException if the fixture or the environment cannot be built
	 */
	@Test(dataProvider = "sourceTokens")
	public void keepsSourceTokenOutOfEnvironment(String variable, String expected) throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-source-token-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.settings(source, """
				{"env": {"%s": "%s"}}
				""".formatted(variable, SourceHomes.SECRET));

			Map<String, String> result = sessionEnvironment(scope, root, source, null);

			requireNoToken(result);
			requireThat(result.get("CAT_HOST_LOGIN"), "hostLogin").isEqualTo(expected);
		}
	}

	/**
	 * Returns the token variables that select a credential directly, with the class of login that each selects.
	 *
	 * @return the variables and classes
	 */
	@DataProvider
	public static Object[][] sourceTokens()
	{
		return new Object[][]
		{
			{"ANTHROPIC_API_KEY", "api_key"},
			{"CLAUDE_CODE_OAUTH_TOKEN", "subscription"}
		};
	}

	/**
	 * Verifies that a source whose selected credential is a {@code claude setup-token} token keeps the class
	 * {@code subscription} when a stored login lies beside it, and takes the declared class when the source also names
	 * a base URL, while the token stays out of the session's Claude process.
	 *
	 * @throws IOException if the fixture or the environment cannot be built
	 */
	@Test
	public void setupTokenKeepsSubscription() throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-source-setup-token-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.storedLogin(source);
			SourceHomes.settings(source, """
				{"env": {"CLAUDE_CODE_OAUTH_TOKEN": "%s"}}
				""".formatted(SourceHomes.SETUP_TOKEN));
			Path gateway = SourceHomes.create(root, "gateway");
			SourceHomes.settings(gateway, """
				{"env": {"CLAUDE_CODE_OAUTH_TOKEN": "%s", "ANTHROPIC_BASE_URL": "https://gateway.example"}}
				""".formatted(SourceHomes.SETUP_TOKEN));

			Map<String, String> alone = sessionEnvironment(scope, root, source, null);
			Map<String, String> behindGateway = sessionEnvironment(scope, root, gateway, "api_key");

			requireNoToken(alone);
			requireThat(alone.get("CAT_HOST_LOGIN"), "aloneHostLogin").isEqualTo("subscription");
			requireNoToken(behindGateway);
			requireThat(behindGateway.get("CAT_HOST_LOGIN"), "gatewayHostLogin").isEqualTo("api_key");
		}
	}

	/**
	 * Verifies that a gateway's base URL in the source's settings reaches the session's Claude process while its token
	 * does not, and that the process states the class of login that the launch declares.
	 *
	 * @param declared the declared class
	 * @throws IOException if the fixture or the environment cannot be built
	 */
	@Test(dataProvider = "loginClasses")
	public void passesGatewayWithDeclaredClass(String declared) throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-source-gateway-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.settings(source, """
				{"env": {"ANTHROPIC_AUTH_TOKEN": "%s", "ANTHROPIC_BASE_URL": "https://gateway.example"}}
				""".formatted(SourceHomes.SECRET));

			Map<String, String> result = sessionEnvironment(scope, root, source, declared);

			requireNoToken(result);
			requireThat(result.get("ANTHROPIC_BASE_URL"), "baseUrl").isEqualTo("https://gateway.example");
			requireThat(result.get("CAT_HOST_LOGIN"), "hostLogin").isEqualTo(declared);
		}
	}

	/**
	 * Returns the classes of login that a launch may declare for a gateway.
	 *
	 * @return the classes
	 */
	@DataProvider
	public static Object[][] loginClasses()
	{
		return new Object[][]{{"subscription"}, {"api_key"}};
	}

	/**
	 * Requires that the environment of a session's Claude process holds no token variable, no request to scrub its
	 * commands' environment and no fixture token as any variable's value.
	 *
	 * @param environment the environment
	 */
	private static void requireNoToken(Map<String, String> environment)
	{
		requireThat(environment.keySet(), "variables").doesNotContainAny(List.of("ANTHROPIC_API_KEY",
			"ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB"));
		for (Map.Entry<String, String> entry : environment.entrySet())
		{
			requireThat(entry.getValue(), entry.getKey()).doesNotContain(SourceHomes.SECRET).
				doesNotContain(SourceHomes.SETUP_TOKEN);
		}
	}

	/**
	 * Verifies that a source whose only credential is an {@code apiKeyHelper} gives the session's Claude process no
	 * token and no scrub, and the class {@code api_key}.
	 *
	 * @throws IOException if the fixture or the environment cannot be built
	 */
	@Test
	public void keyHelperGivesNoToken() throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-source-helper-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.settings(source, """
				{"apiKeyHelper": "printf key"}
				""");

			Map<String, String> result = sessionEnvironment(scope, root, source, null);

			for (String variable : List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN",
				"CLAUDE_CODE_SUBPROCESS_ENV_SCRUB"))
			{
				requireThat(result.keySet(), "variables").doesNotContain(variable);
			}
			requireThat(result.get("CAT_HOST_LOGIN"), "hostLogin").isEqualTo("api_key");
			requireThat(result.keySet(), "variables").doesNotContain("CLAUDE_SECURESTORAGE_CONFIG_DIR");
		}
	}

	/**
	 * Verifies that a source whose settings name no credential delivers the token of CAT's credential file through the
	 * session's key file, with the class that the token's prefix gives, or the declared class when the source names a
	 * base URL, and that the evidence names the file by its path in the source home.
	 *
	 * @param token      the file's token
	 * @param gateway    {@code true} if the source's settings name a base URL
	 * @param declared   the class that the launch declares, or {@code null} for none
	 * @param loginClass the class that {@code CAT_HOST_LOGIN} states
	 * @throws IOException if the fixture or the environment cannot be built
	 */
	@Test(dataProvider = "catCredentials")
	public void deliversCatCredentialFile(String token, boolean gateway, String declared, String loginClass)
		throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-credential-file-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.catCredential(source, token);
			List<String> parts = new ArrayList<>();
			if (gateway)
			{
				SourceHomes.settings(source, """
					{"env": {"ANTHROPIC_BASE_URL": "https://gateway.example"}}
					""");
				parts.add("ANTHROPIC_BASE_URL");
			}
			parts.add(".cat/claude/.credentials.json");
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, declared), Map.of(),
				Platform.current(), SourceHomes.PLUGIN);
			Path claudeFolder = Files.createDirectories(root.resolve("session-home/.claude"));

			Map<String, String> result = sessionEnvironment(scope, root, source, declared);
			authentication.writeKeyFile(claudeFolder);

			requireNoToken(result);
			requireThat(result.get("CAT_HOST_LOGIN"), "hostLogin").isEqualTo(loginClass);
			requireThat(Files.readString(ClaudeAuthentication.keyFile(claudeFolder)), "keyFile").isEqualTo(token);
			requireThat(authentication.evidence().parts(), "parts").isEqualTo(parts);
			requireThat(AuthenticationOptions.classValue(authentication.evidence().loginClass()), "loginClass").
				isEqualTo(loginClass);
		}
	}

	/**
	 * Returns the tokens of CAT's credential file, each with whether the source names a base URL, the class that the
	 * launch declares and the class that the session states.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] catCredentials()
	{
		return new Object[][]
		{
			{SourceHomes.SECRET, false, null, "api_key"},
			{SourceHomes.SETUP_TOKEN, false, null, "subscription"},
			{SourceHomes.SETUP_TOKEN, true, "api_key", "api_key"}
		};
	}

	/**
	 * Verifies that a credential that the source's settings name outranks CAT's credential file, which Claude Code's
	 * order ranks last, in the stored login's place: the session receives that credential, and the file's token reaches
	 * no key file.
	 *
	 * @param settings  the source's {@code settings.json}
	 * @param delivered the token that the key file holds, or {@code null} if the source helper is selected
	 * @param part      the name of the part that the session receives
	 * @throws IOException if the fixture cannot be built
	 */
	@Test(dataProvider = "credentialsBesideCatFile")
	public void settingsCredentialOutranksCatFile(String settings, String delivered, String part) throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-credential-rank-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.secret(source.resolve("key"));
			SourceHomes.catCredential(source, FILE_TOKEN);
			SourceHomes.settings(source, settings);
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, "subscription"), Map.of(),
				Platform.current(), SourceHomes.PLUGIN);
			Path claudeFolder = Files.createDirectories(root.resolve("session-home/.claude"));

			authentication.writeKeyFile(claudeFolder);

			Path keyFile = ClaudeAuthentication.keyFile(claudeFolder);
			if (delivered == null)
				requireThat(Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS), "keyFileExists").isFalse();
			else
				requireThat(Files.readString(keyFile), "keyFile").isEqualTo(delivered);
			requireThat(authentication.evidence().parts(), "parts").contains(part).
				doesNotContain(".cat/claude/.credentials.json");
		}
	}

	/**
	 * Returns sources whose settings name a credential beside CAT's credential file, each with the token that the key
	 * file then holds, or {@code null} for the source helper, and the name of the delivered part.
	 *
	 * @return the sources
	 */
	@DataProvider
	public static Object[][] credentialsBesideCatFile()
	{
		return new Object[][]
		{
			{"""
				{"apiKeyHelper": "cat ~/key"}
				""", null, "apiKeyHelper"},
			{"""
				{"env": {"ANTHROPIC_AUTH_TOKEN": "%s", "ANTHROPIC_BASE_URL": "https://gateway.example"}}
				""".formatted(SourceHomes.SECRET), SourceHomes.SECRET, "ANTHROPIC_AUTH_TOKEN"},
			{"""
				{"env": {"ANTHROPIC_API_KEY": "%s"}}
				""".formatted(SourceHomes.SECRET), SourceHomes.SECRET, "ANTHROPIC_API_KEY"},
			{"""
				{"env": {"CLAUDE_CODE_OAUTH_TOKEN": "%s"}}
				""".formatted(SourceHomes.SETUP_TOKEN), SourceHomes.SETUP_TOKEN, "CLAUDE_CODE_OAUTH_TOKEN"}
		};
	}

	/**
	 * Verifies that without {@code --home} the runner reads CAT's credential file in its own home folder, even when the
	 * runner's {@code CLAUDE_CONFIG_DIR} names another Claude folder, because that variable says only where Claude Code
	 * keeps its configuration.
	 *
	 * @throws IOException if the fixture cannot be built
	 */
	@Test
	public void readsCatCredentialFileOfRunnerHome() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-credential-home-")).toRealPath();
			Path runnerHome = SourceHomes.create(root, "runner");
			Path file = SourceHomes.catCredential(runnerHome, SourceHomes.SECRET);
			Path configured = Files.createDirectories(root.resolve("configured"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				runnerHome.toString(), "CLAUDE_CONFIG_DIR", configured.toString());

			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, AuthenticationOptions.DEFAULT,
				environment, Platform.current(), SourceHomes.PLUGIN);

			requireThat(authentication.evidence().parts(), "parts").isEqualTo(List.of(".cat/claude/.credentials.json"));
			requireThat(authentication.secretFiles(), "secretFiles").contains(file);
		}
	}

	/**
	 * Verifies that a session shares no {@code /login} login: for a source home that holds a stored login and CAT's
	 * credential file, under a runner environment that sets {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} empty or to a
	 * folder, the session's Claude process gets no {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}, its
	 * {@code CLAUDE_CONFIG_DIR} is the session's own Claude folder, and that folder holds no {@code .credentials.json}
	 * when the Claude process would start. A login that the session's Claude Code stores, simulated here by writing
	 * {@code .credentials.json} into the folder that its {@code CLAUDE_CONFIG_DIR} names as {@code /login} would, leaves
	 * the source's login byte for byte and time for time unchanged, and the session's settings written again for a
	 * resume still deliver the key file.
	 *
	 * @param loginStore the runner's {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}, relative to the test's folder, or an empty
	 *                   string
	 * @throws IOException if the fixture or the settings cannot be built
	 */
	@Test(dataProvider = "runnerLoginStores")
	public void sharesNoLogin(String loginStore) throws IOException
	{
		// Requirement: R-I14
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-no-shared-login-")).
				toRealPath();
			Path source = SourceHomes.create(root, "source");
			Path sourceLogin = SourceHomes.storedLogin(source);
			SourceHomes.catCredential(source, SourceHomes.SETUP_TOKEN);
			byte[] loginBytes = Files.readAllBytes(sourceLogin);
			FileTime loginTime = Files.getLastModifiedTime(sourceLogin);
			String storeValue = loginStore;
			if (!loginStore.isEmpty())
				storeValue = Files.createDirectories(root.resolve(loginStore)).toString();
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString(), "CLAUDE_SECURESTORAGE_CONFIG_DIR", storeValue);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			Path project = Files.createDirectories(nestedSession.directory().resolve("project/.git")).getParent();
			Path home = nestedSession.directory().resolve("home");
			Path claudeFolder = home.resolve(".claude");
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, null), environment,
				Platform.current(), SourceHomes.PLUGIN);

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment, HarnessDependencyCaches.NONE,
				root.resolve("missing-settings.json"), home, null, authentication);
			writeSettings(scope, root, nestedSession, project, authentication);
			authentication.writeKeyFile(claudeFolder);

			requireThat(result.keySet(), "variables").doesNotContain("CLAUDE_SECURESTORAGE_CONFIG_DIR");
			requireThat(result.get("CLAUDE_CONFIG_DIR"), "configurationFolder").isEqualTo(claudeFolder.toString());
			requireThat(Files.exists(claudeFolder.resolve(".credentials.json"), LinkOption.NOFOLLOW_LINKS),
				"sessionLoginExists").isFalse();

			// The session's Claude Code stores a /login login in the folder that its CLAUDE_CONFIG_DIR names
			Files.writeString(Path.of(result.get("CLAUDE_CONFIG_DIR")).resolve(".credentials.json"), "{}");

			requireThat(Files.readAllBytes(sourceLogin), "sourceLogin").isEqualTo(loginBytes);
			requireThat(Files.getLastModifiedTime(sourceLogin), "sourceLoginTime").isEqualTo(loginTime);
			writeSettings(scope, root, nestedSession, project, ClaudeAuthentication.read(JSON, named(source, null),
				environment, Platform.current(), SourceHomes.PLUGIN));
			String helper = JSON.readTree(Files.readString(claudeFolder.resolve("settings.json"))).path("apiKeyHelper").
				asString("");
			requireThat(helper, "resumedHelper").isEqualTo("cat '" + ClaudeAuthentication.keyFile(claudeFolder) + "'");
		}
	}

	/**
	 * Returns the runner's values of {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}: empty, and a folder relative to the test's
	 * folder.
	 *
	 * @return the values
	 */
	@DataProvider
	public static Object[][] runnerLoginStores()
	{
		return new Object[][]{{""}, {"runner-login-store"}};
	}

	/**
	 * Writes a nested session's settings for a source home's authentication.
	 *
	 * @param scope          the runner's scope
	 * @param root           the test's folder
	 * @param nestedSession  the nested session
	 * @param project        the nested session's project
	 * @param authentication the source home's authentication
	 * @throws IOException if the settings cannot be written
	 */
	private static void writeSettings(HarnessRunnerScope scope, Path root, NestedSession nestedSession, Path project,
		ClaudeAuthentication authentication) throws IOException
	{
		ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"),
			nestedSession.directory().resolve("home/.claude/settings.json"), nestedSession.directory().resolve("claude"),
			nestedSession.directory().resolve("home"), List.of(), nestedSession.directory(), List.of(), List.of(), "",
			null, project, nestedSession.directory(), null, ExtraPermissions.NONE, "api.anthropic.com", "", true,
			List.of(), authentication);
	}

	/**
	 * Verifies that a Claude child needs the host of the source's base URL, or Anthropic's API host when the source
	 * names none, whatever host the runner's own {@code ANTHROPIC_BASE_URL} names, and the source files that its runner
	 * reads, which the request server checks against the requester's policy.
	 *
	 * @throws IOException if the fixture or the report cannot be built
	 */
	@Test
	public void childNeedsSourceModelHost() throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-source-child-needs-")).toRealPath();
			Path runnerHome = SourceHomes.create(root, "runner");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				runnerHome.toString(), "ANTHROPIC_BASE_URL", "https://runner.example");

			Path gateway = SourceHomes.create(root, "gateway");
			Path gatewaySettings = SourceHomes.settings(gateway, """
				{"env": {"ANTHROPIC_AUTH_TOKEN": "%s", "ANTHROPIC_BASE_URL": "https://Gateway.Example:8443/v1"}}
				""".formatted(SourceHomes.SECRET));
			ChildNeedsReport gatewayNeeds = ClaudeRunner.childNeeds(scope, environment, ClaudeAuthentication.read(JSON,
				named(gateway, "api_key"), environment, Platform.current(), SourceHomes.PLUGIN));
			requireThat(gatewayNeeds.hosts(), "gatewayHosts").isEqualTo(Set.of("gateway.example"));
			requireThat(gatewayNeeds.logins(), "gatewayLogins").contains(gatewaySettings);

			Path helper = SourceHomes.create(root, "helper");
			SourceHomes.settings(helper, """
				{"apiKeyHelper": "printf key"}
				""");
			ChildNeedsReport helperNeeds = ClaudeRunner.childNeeds(scope, environment, ClaudeAuthentication.read(JSON,
				named(helper, null), environment, Platform.current(), SourceHomes.PLUGIN));
			requireThat(helperNeeds.hosts(), "helperHosts").isEqualTo(Set.of("api.anthropic.com"));
		}
	}

	/**
	 * Verifies that the session's settings hold the source's {@code apiKeyHelper}, wrapped so that it runs with the
	 * source home as its home folder, and its helper lifetime, but none of the source's hooks, permissions, plugins or
	 * MCP servers; that a POSIX shell that runs the wrapped helper with another home folder prints the key that the
	 * helper reads from {@code ~/key}; and that no element of the session's command holds the key.
	 *
	 * @throws IOException          if the fixture, the settings or the shell's output cannot be read or written
	 * @throws InterruptedException if the test is interrupted while the shell runs
	 */
	@Test
	public void copiesOnlyWrappedKeyHelper() throws IOException, InterruptedException
	{
		// Requirement: R-I14
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-source-settings-")).
				toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.secret(source.resolve("key"));
			SourceHomes.settings(source, """
				{
				  "apiKeyHelper": "cat ~/key",
				  "env": {"CLAUDE_CODE_API_KEY_HELPER_TTL_MS": "60000"},
				  "hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "echo source-hook"}]}]},
				  "permissions": {"deny": ["Bash(source-denial:*)"], "allow": ["Bash(source-grant:*)"]},
				  "enabledPlugins": {"source-plugin@source-marketplace": true},
				  "mcpServers": {"source-server": {"command": "source-server"}}
				}
				""");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString());
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, null), environment,
				Platform.current(), SourceHomes.PLUGIN);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			Path project = Files.createDirectories(nestedSession.directory().resolve("project/.git")).getParent();
			Path settingsFile = nestedSession.directory().resolve("home/.claude/settings.json");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), settingsFile,
				nestedSession.directory().resolve("claude"), nestedSession.directory().resolve("home"), List.of(),
				nestedSession.directory(), List.of(), List.of(), "", null, project, nestedSession.directory(), null,
				ExtraPermissions.NONE, "api.anthropic.com", "", true, List.of(), authentication);

			JsonNode settings = JSON.readTree(Files.readString(settingsFile));
			String helper = settings.path("apiKeyHelper").asString("");
			requireThat(helper, "helper").contains("cat ~/key").contains(source.toString());
			requireThat(settings.path("env").path("CLAUDE_CODE_API_KEY_HELPER_TTL_MS").asString(""), "ttl").
				isEqualTo("60000");
			for (String setting : List.of("hooks", "enabledPlugins", "mcpServers"))
				requireThat(settings.has(setting), setting).isFalse();
			requireThat(settings.toString(), "settings").doesNotContain("source-denial").doesNotContain("source-grant");

			Path otherHome = Files.createDirectories(root.resolve("other-home"));
			ProcessBuilder shell = new ProcessBuilder("sh", "-c", helper).redirectErrorStream(true);
			shell.environment().put("HOME", otherHome.toString());
			requireThat(printedByHelper(shell), "printed").isEqualTo(SourceHomes.SECRET);

			List<String> command = ClaudeRunner.command("claude", "haiku", "low", "", List.of(),
				nestedSession.directory().resolve("home/.claude/projects"), List.of());
			for (String element : command)
				requireThat(element, "element").doesNotContain(SourceHomes.SECRET);
		}
	}

	/**
	 * Verifies that each source file that holds a secret, CAT's credential file included, and the source's stored login
	 * are denied to the session's commands and file tools, through
	 * {@code sandbox.filesystem.denyRead} and a {@code Read} rule in {@code permissions.deny}, and stays in the denied
	 * reads that the session's runner passes to every session that the session requests.
	 *
	 * @throws IOException if the fixture or the settings cannot be built
	 */
	@Test
	public void deniesSourceSecretFiles() throws IOException
	{
		// Requirement: R-I14
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-source-denials-")).
				toRealPath();
			Path source = SourceHomes.create(root, "source");
			Path key = SourceHomes.secret(source.resolve("key"));
			Path login = SourceHomes.storedLogin(source);
			Path catCredential = SourceHomes.catCredential(source, SourceHomes.SETUP_TOKEN);
			Path sourceSettings = SourceHomes.settings(source, """
				{"apiKeyHelper": "cat ~/key", "env": {"CLAUDE_CODE_OAUTH_TOKEN": "%s"}}
				""".formatted(SourceHomes.SECRET));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString());
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, null), environment,
				Platform.current(), SourceHomes.PLUGIN);
			HarnessRunnerOptions options = authentication.withDeniedReads(ClaudeRunnerCli.options(new String[]{
				"--artifact", root.resolve("artifact").toString(), "--results", root.resolve("results").toString(),
				"--model", "haiku", "--effort", "low"}, HarnessTemporaryRoot.withoutSession("no session")));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			manager.snapshotProject(nestedSession);
			Path project = Files.createDirectories(nestedSession.directory().resolve("project/.git")).getParent();
			Path settingsFile = nestedSession.directory().resolve("home/.claude/settings.json");
			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, nestedSession, List.of(),
				options.deniedReads(), environment);

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), settingsFile,
				nestedSession.directory().resolve("claude"), nestedSession.directory().resolve("home"), List.of(),
				nestedSession.directory(), deniedReads, List.of(), "", null, project, nestedSession.directory(), null,
				ExtraPermissions.NONE, "api.anthropic.com", "", true, List.of(), authentication);

			// The source's stored login holds the user's own login, which the runner never reads but denies
			List<Path> secrets = List.of(key, catCredential, sourceSettings, login);
			requireThat(options.deniedReads(), "requestedSessionDenials").containsAll(secrets);
			JsonNode settings = JSON.readTree(Files.readString(settingsFile));
			List<String> denyRead = new ArrayList<>();
			for (JsonNode path : settings.path("sandbox").path("filesystem").path("denyRead"))
				denyRead.add(path.asString());
			List<String> denyRules = new ArrayList<>();
			for (JsonNode rule : settings.path("permissions").path("deny"))
				denyRules.add(rule.asString());
			for (Path secret : secrets)
			{
				requireThat(denyRead, "denyRead").contains(secret.toString());
				requireThat(denyRules, "denyRules").contains("Read(/" + ClaudeConnectionSettings.rulePath(secret) + ")");
			}
		}
	}

	/**
	 * Verifies that the files that the runner writes into a nested session's folder before its harness starts, other
	 * than the session's own settings, hold no secret of the source home, and that neither does the command that
	 * {@code session.json} records, while {@code session.json} records the source home, the name of the one part that
	 * the session receives among the several that the source holds, and the class of login.
	 *
	 * @throws IOException if the fixture, the settings or the records cannot be read or written
	 */
	@Test
	public void recordsAuthenticationWithoutSecrets() throws IOException
	{
		// Requirement: R-I14
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-source-records-")).
				toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.secret(source.resolve("key"));
			SourceHomes.storedLogin(source);
			SourceHomes.settings(source, """
				{"apiKeyHelper": "cat ~/key", "env": {"ANTHROPIC_API_KEY": "%s", "CLAUDE_CODE_OAUTH_TOKEN": "%s"}}
				""".formatted(SourceHomes.SECRET, SourceHomes.SECRET));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString());
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, null), environment,
				Platform.current(), SourceHomes.PLUGIN);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			Path project = Files.createDirectories(nestedSession.directory().resolve("project/.git")).getParent();
			Path settingsFile = nestedSession.directory().resolve("home/.claude/settings.json");
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), settingsFile,
				nestedSession.directory().resolve("claude"), nestedSession.directory().resolve("home"), List.of(),
				nestedSession.directory(), List.of(), List.of(), "", null, project, nestedSession.directory(), null,
				ExtraPermissions.NONE, "api.anthropic.com", "", true, List.of(), authentication);
			List<String> command = ClaudeRunner.command("claude", "haiku", "low", "", List.of(),
				nestedSession.directory().resolve("home/.claude/projects"), List.of());

			manager.initialize(nestedSession, root.resolve("artifact"), project, "claude_stream_json", command,
				authentication.evidence());

			JsonNode manifest = JSON.readTree(Files.readString(nestedSession.directory().resolve("session.json")));
			JsonNode recorded = manifest.path("authentication");
			requireThat(recorded.path("home").asString(""), "home").isEqualTo(source.toString());
			List<String> parts = new ArrayList<>();
			for (JsonNode part : recorded.path("parts"))
				parts.add(part.asString());
			// Claude Code's order selects the API key, so it is the one part that the session receives
			requireThat(parts, "parts").isEqualTo(List.of("ANTHROPIC_API_KEY"));
			requireThat(recorded.path("login_class").asString(""), "loginClass").isEqualTo("api_key");
			try (Stream<Path> files = Files.walk(nestedSession.directory()))
			{
				for (Path file : files.filter(Files::isRegularFile).filter(file -> !file.equals(settingsFile)).toList())
					requireThat(Files.readString(file), file.toString()).doesNotContain(SourceHomes.SECRET);
			}
			requireThat(manifest.path("harness_command").asString(""), "command").doesNotContain(SourceHomes.SECRET);
		}
	}

	/**
	 * Verifies that the runner chooses the one credential that fills the session's {@code apiKeyHelper} by Claude
	 * Code's order: a selected token reaches Claude Code only through the session's key file, {@code cat-api-key} in its
	 * {@code .claude} folder, which holds exactly the token's bytes, is readable by its owner alone, and which a POSIX
	 * {@code sh} that runs the session's helper prints; a selected source helper is copied, wrapped, and no key file is
	 * written. The key file is denied to the session's commands and file tools and to every session that the session
	 * requests, and neither the session's command, its settings nor {@code session.json} holds the token.
	 *
	 * @param settings    the source's {@code settings.json}
	 * @param storedLogin {@code true} if the source also holds a stored login
	 * @param delivered   the token that the key file holds, or {@code null} if the source helper is selected
	 * @throws IOException          if the fixture, the settings or the helper's output cannot be read or written
	 * @throws InterruptedException if the test is interrupted while the shell runs
	 */
	@Test(dataProvider = "rankedCredentials")
	public void deliversCredentialByClaudeCodeOrder(String settings, boolean storedLogin, String delivered)
		throws IOException, InterruptedException
	{
		// Requirement: R-I14
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-key-file-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			SourceHomes.secret(source.resolve("key"));
			if (storedLogin)
				SourceHomes.storedLogin(source);
			SourceHomes.settings(source, settings);
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				SourceHomes.create(root, "runner").toString());
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, null), environment,
				Platform.current(), SourceHomes.PLUGIN);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			manager.snapshotProject(nestedSession);
			Path project = Files.createDirectories(nestedSession.directory().resolve("project/.git")).getParent();
			Path claudeFolder = nestedSession.directory().resolve("home/.claude");
			Path catPlugin = Files.createDirectories(claudeFolder.resolve("plugins/cat-marketplace/plugins/cat"));
			Path settingsFile = claudeFolder.resolve("settings.json");
			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, nestedSession, List.of(), List.of(),
				environment);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), settingsFile,
				nestedSession.directory().resolve("claude"), nestedSession.directory().resolve("home"), List.of(),
				nestedSession.directory(), deniedReads, List.of(), "", null, project, nestedSession.directory(), null,
				ExtraPermissions.NONE, "api.anthropic.com", "", true, List.of(), authentication);

			authentication.writeKeyFile(claudeFolder);

			Path keyFile = ClaudeAuthentication.keyFile(claudeFolder);
			JsonNode sessionSettings = JSON.readTree(Files.readString(settingsFile));
			String helper = sessionSettings.path("apiKeyHelper").asString("");
			if (delivered == null)
			{
				requireThat(helper, "helper").contains("cat ~/key").contains(source.toString());
				requireThat(Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS), "keyFileExists").isFalse();
				return;
			}
			requireThat(helper, "helper").isEqualTo("cat '" + keyFile + "'");
			requireThat(Files.readString(keyFile), "keyFile").isEqualTo(delivered);
			SourceHomes.requireOwnerOnly(keyFile);
			ProcessBuilder shell = new ProcessBuilder("sh", "-c", helper).redirectErrorStream(true);
			requireThat(printedByHelper(shell), "printed").isEqualTo(delivered);
			List<String> denyRead = new ArrayList<>();
			for (JsonNode path : sessionSettings.path("sandbox").path("filesystem").path("denyRead"))
				denyRead.add(path.asString());
			List<String> denyRules = new ArrayList<>();
			for (JsonNode rule : sessionSettings.path("permissions").path("deny"))
				denyRules.add(rule.asString());
			requireThat(denyRead, "denyRead").contains(keyFile.toString());
			requireThat(denyRules, "denyRules").contains("Read(/" + ClaudeConnectionSettings.rulePath(keyFile) + ")");
			boolean deniedToRequested = false;
			for (Path denial : SessionRequestBroker.resultsDenials(root.resolve("results"), catPlugin))
				deniedToRequested |= keyFile.startsWith(denial);
			requireThat(deniedToRequested, "deniedToRequestedSessions").isTrue();

			List<String> command = ClaudeRunner.command("claude", "haiku", "low", "", List.of(),
				claudeFolder.resolve("projects"), List.of());
			manager.initialize(nestedSession, root.resolve("artifact"), project, "claude_stream_json", command,
				authentication.evidence());
			for (String element : command)
				requireThat(element, "element").doesNotContain(delivered);
			try (Stream<Path> files = Files.walk(nestedSession.directory()))
			{
				for (Path file : files.filter(Files::isRegularFile).filter(file -> !file.equals(keyFile)).toList())
					requireThat(Files.readString(file), file.toString()).doesNotContain(delivered);
			}
		}
	}

	/**
	 * Returns sources that hold several credentials, each with the token that Claude Code's order selects among them,
	 * or {@code null} when it selects the source helper.
	 *
	 * @return the sources
	 */
	@DataProvider
	public static Object[][] rankedCredentials()
	{
		String bearer = SourceHomes.SECRET + "-bearer";
		return new Object[][]
		{
			// The bearer token outranks the API key and the helper
			{"""
				{"apiKeyHelper": "cat ~/key", "env": {"ANTHROPIC_AUTH_TOKEN": "%s", "ANTHROPIC_API_KEY": "%s-key"}}
				""".formatted(bearer, SourceHomes.SECRET), false, bearer},
			// The API key outranks the helper
			{"""
				{"apiKeyHelper": "cat ~/key", "env": {"ANTHROPIC_API_KEY": "%s-key"}}
				""".formatted(SourceHomes.SECRET), false, SourceHomes.SECRET + "-key"},
			// The helper outranks the setup token
			{"""
				{"apiKeyHelper": "cat ~/key", "env": {"CLAUDE_CODE_OAUTH_TOKEN": "%s"}}
				""".formatted(SourceHomes.SETUP_TOKEN), false, null},
			// The setup token outranks the stored login
			{"""
				{"env": {"CLAUDE_CODE_OAUTH_TOKEN": "%s"}}
				""".formatted(SourceHomes.SETUP_TOKEN), true, SourceHomes.SETUP_TOKEN}
		};
	}

	/**
	 * Verifies that the key file exists while the session's Claude process runs and is gone once the session is
	 * closed, whether the process exited by itself or the close stopped it. The stand-in for Claude Code is an
	 * in-process {@link FakeHarnessCli}, which neither reads nor writes the key file.
	 *
	 * @param stopped {@code true} if the close must stop a process that keeps running, {@code false} if the process
	 *                exits by itself first
	 * @throws IOException if the fixture or the session fails
	 */
	@Test(dataProvider = "processEnds", timeOut = 10_000)
	// The test closes the session itself, because the close is the step whose effect it checks
	@SuppressWarnings("PMD.CloseResource")
	public void removesKeyFileWhenClaudeEnds(boolean stopped) throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-key-file-lifetime-")).toRealPath();
			Path claudeFolder = Files.createDirectories(root.resolve("session/home/.claude"));
			ClaudeAuthentication authentication = apiKeySource(root, "source", SourceHomes.SECRET);
			authentication.writeKeyFile(claudeFolder);
			Path keyFile = ClaudeAuthentication.keyFile(claudeFolder);
			FakeHarnessCli claude = new FakeHarnessCli(_ ->
			{
			});
			if (stopped)
				claude.keepRunningWhenInputCloses();
			HarnessProcess session = ClaudeRunner.removingKeyFile(new ClaudeProcess(scope, claude, null, null,
				Duration.ofMillis(50)), claudeFolder);
			requireThat(Files.readString(keyFile), "whileRunning").isEqualTo(SourceHomes.SECRET);
			if (!stopped)
				claude.exit();

			session.close();

			requireThat(claude.isAlive(), "claudeAlive").isFalse();
			requireThat(Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS), "keyFileExists").isFalse();
		}
	}

	/**
	 * Returns whether a session's Claude process is stopped by the close rather than exiting by itself.
	 *
	 * @return the choices
	 */
	@DataProvider
	public static Object[][] processEnds()
	{
		return new Object[][]{{false}, {true}};
	}

	/**
	 * Verifies that a resume writes the key file again from the resuming launch's source home over a file that an
	 * earlier launch left, owner-only, and that a resume whose source selects its helper removes such a file.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void resumeWritesResumingSourceToken() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-key-file-resume-")).toRealPath();
			Path claudeFolder = Files.createDirectories(root.resolve("session/home/.claude"));
			Path keyFile = ClaudeAuthentication.keyFile(claudeFolder);
			apiKeySource(root, "first", SourceHomes.SECRET).writeKeyFile(claudeFolder);
			Path second = SourceHomes.create(root, "second");
			SourceHomes.settings(second, """
				{"env": {"CLAUDE_CODE_OAUTH_TOKEN": "%s"}}
				""".formatted(SourceHomes.SETUP_TOKEN));

			ClaudeAuthentication.read(JSON, named(second, null), Map.of(), Platform.current(), SourceHomes.PLUGIN).
				writeKeyFile(claudeFolder);

			requireThat(Files.readString(keyFile), "resumed").isEqualTo(SourceHomes.SETUP_TOKEN);
			SourceHomes.requireOwnerOnly(keyFile);

			SourceHomes.keyHelper(root).writeKeyFile(claudeFolder);

			requireThat(Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS), "keyFileExists").isFalse();
		}
	}

	/**
	 * Verifies that emptying a slot deletes a key file that a stopped runner left in the slot's nested session.
	 *
	 * @throws IOException          if the fixture or the slot cannot be written
	 * @throws InterruptedException if the test is interrupted while it takes the slot
	 */
	@Test
	public void emptyingSlotDeletesLeftKeyFile() throws IOException, InterruptedException
	{
		// Requirement: R-I14
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-key-file-slot-")).
				toRealPath();
			SessionSlots slots = new SessionSlots(root.resolve("slots"), 1, new ProcessHandleLiveness(), jvm.fileLocks());
			SessionSlots.Slot slot = slots.acquire(false);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("claude");
			Path claudeFolder = Files.createDirectories(nestedSession.directory().resolve("home/.claude"));
			apiKeySource(root, "source", SourceHomes.SECRET).writeKeyFile(claudeFolder);

			slots.release(slot);

			requireThat(Files.exists(ClaudeAuthentication.keyFile(claudeFolder), LinkOption.NOFOLLOW_LINKS),
				"keyFileExists").isFalse();
		}
	}

	/**
	 * Verifies that a launch whose pre-launch check fails is refused with a {@link SessionRefusedException} before the
	 * results folder holds a nested session and without starting the Claude executable, for a new session and for one
	 * that resumes a session, with a {@code TMPDIR} in the project's settings that is longer than the sandbox's
	 * sockets allow on Linux and macOS, and with the native Windows refusal on Windows.
	 *
	 * @throws IOException if a fixture cannot be created
	 */
	@Test
	public void refusesLaunchWhoseSandboxWouldNotStart() throws IOException
	{
		// Requirement: R-I16
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-sandbox-start-")).toRealPath();
			Path project = Files.createDirectories(root.resolve("project/.claude"));
			Files.writeString(project.resolve("settings.json"), """
				{"env": {"TMPDIR": "/%s"}}
				""".formatted("t".repeat(100)));
			Path results = root.resolve("results");
			Path started = root.resolve("claude-started");
			Path executable = marker(root, started);
			Path home = SourceHomes.create(root, "source");
			ClaudeAuthentication authentication = SourceHomes.keyHelper(home);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), results,
				Clock.systemUTC(), Yielding.fileReplacer());
			AtomicReference<ChildPolicy> listened = new AtomicReference<>();
			for (String resumed : new String[]{null, "session-that-does-not-exist"})
			{
				try
				{
					new ClaudeRunner(Yielding.waiting()).start(root.resolve("artifact"), root.resolve("project"), null,
						List.of(), manager, executable.toString(), Map.of("PATH", ""), "sonnet", "low", null, false,
						List.of(), List.of(), "", false, 0, null, listened::set, ExtraPermissions.NONE,
						ClaudeSessionRole.TRIAL, resumed, authentication);
					throw new AssertionError("The launch was not refused");
				}
				catch (SessionRefusedException e)
				{
					if (Platform.current().isWindows())
						requireThat(e.getMessage(), "message").contains("native Windows");
					else
						requireThat(e.getMessage(), "message").contains("env.TMPDIR").contains("101 bytes");
				}
				requireThat(Files.exists(started), "claudeStarted").isFalse();
				requireThat(Files.exists(results), "results").isFalse();
			}
			requireThat(Files.exists(root.resolve("artifact")), "artifactRead").isFalse();
		}
	}

	/**
	 * Verifies that a session that runs without a sandbox is not refused for the causes that stop a sandbox, since it
	 * starts none, so that the check reads the session's role.
	 *
	 * @throws IOException if a fixture cannot be created
	 */
	@Test
	public void skipsSandboxCheckWithoutSandbox() throws IOException
	{
		// Requirement: R-I16
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-no-sandbox-start-")).toRealPath();
			Path project = Files.createDirectories(root.resolve("project/.claude"));
			Files.writeString(project.resolve("settings.json"), """
				{"env": {"TMPDIR": "/%s"}}
				""".formatted("t".repeat(100)));
			Path home = SourceHomes.create(root, "source");
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(),
				root.resolve("results"), Clock.systemUTC(), Yielding.fileReplacer());
			AtomicReference<ChildPolicy> listened = new AtomicReference<>();
			try
			{
				new ClaudeRunner(Yielding.waiting()).start(root.resolve("artifact"), root.resolve("project"), null,
					List.of(), manager, "claude", Map.of("PATH", ""), "sonnet", "low", null, false, List.of(),
					List.of(), "", false, 0, null, listened::set, ExtraPermissions.NONE, ClaudeSessionRole.JUDGE,
					null, SourceHomes.keyHelper(home));
				throw new AssertionError("The launch started");
			}
			catch (SessionRefusedException e)
			{
				throw new AssertionError("The launch was refused: " + e.getMessage(), e);
			}
			catch (IOException _)
			{
				// The artifact does not exist, so the launch stops after the check that this test shows is skipped
			}
		}
	}

	/**
	 * Verifies that a runner environment that sets {@code TMPDIR}, {@code TMP} and {@code TEMP} to a folder of 200 bytes
	 * gives a Claude process environment without them on Linux and macOS, so that the temporary folder that the
	 * pre-launch check reads is {@code /tmp} and the launch passes the length check, and that the {@code PATH} that the
	 * check searches is the one that the Claude process gets.
	 *
	 * @throws IOException if the environment cannot be built
	 */
	@Test
	public void processHasNoTemporaryVariables() throws IOException
	{
		// Requirement: R-I16
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-temporary-variables-")).toRealPath();
			String folder = "/" + "t".repeat(199);
			Path first = Files.createDirectory(root.resolve("first"));
			Path second = Files.createDirectory(root.resolve("second"));
			String path = String.join(File.pathSeparator, first.toString(), root.resolve("missing").toString(),
				second.toString(), first.toString());
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", path);
			environment.put("TMPDIR", folder);
			environment.put("TMP", folder);
			environment.put("TEMP", folder);
			Path home = SourceHomes.create(root, "session-source");

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, root.resolve("missing-settings.json"), root.resolve("session-home"), null,
				SourceHomes.keyHelper(home));

			requireThat(ClaudeProcessEnvironment.baseEnvironment(environment).get("PATH"), "checkedPath").
				isEqualTo(result.get("PATH")).isEqualTo(String.join(File.pathSeparator, first.toString(),
					second.toString()));
			if (!Platform.current().isWindows())
			{
				requireThat(result.keySet(), "variables").doesNotContain("TMPDIR").doesNotContain("TMP").
					doesNotContain("TEMP");
				requireThat(ClaudeProcessEnvironment.baseEnvironment(environment).keySet(), "checkedVariables").
					doesNotContain("TMPDIR").doesNotContain("TMP").doesNotContain("TEMP");
				requireThat(ClaudeSessionSandbox.temporaryFolder(result), "temporaryFolder").isEqualTo(Path.of("/tmp"));
				requireThat(ClaudeSessionSandbox.startRefusal(Platform.current(), result.get("PATH"),
					ClaudeSessionSandbox.temporaryFolder(result), null, null,
					SeatbeltProfile.SANDBOX_EXECUTABLE, null, null).orElse(""), "refusal").
					doesNotContain("temporary folder");
			}
		}
	}

	/**
	 * Runs a key helper's shell command and returns what it printed, failing if it does not end in time, as it would
	 * if it waited for input that no one sends.
	 *
	 * @param shell the command, whose standard error goes to its standard output
	 * @return the output
	 * @throws IOException          if the command cannot run
	 * @throws InterruptedException if the wait is interrupted
	 */
	// The process ends before the method returns, so there is nothing left to close
	@SuppressWarnings("PMD.CloseResource")
	private static String printedByHelper(ProcessBuilder shell) throws IOException, InterruptedException
	{
		Process process = shell.start();
		process.getOutputStream().close();
		if (!process.waitFor(60, TimeUnit.SECONDS))
		{
			process.destroyForcibly();
			throw new AssertionError("The key helper did not end within 60 seconds");
		}
		String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		requireThat(process.exitValue(), "status").isZero();
		return printed;
	}

	/**
	 * Writes an executable that records that it ran.
	 *
	 * @param directory the directory of the executable
	 * @param marker    the file that the executable creates
	 * @return the executable
	 * @throws IOException if the file cannot be written
	 */
	private static Path marker(Path directory, Path marker) throws IOException
	{
		if (PortableFiles.hasModes(directory))
		{
			Path file = Files.writeString(directory.resolve("claude-fixture.sh"), "#!/bin/sh\n: > '" + marker + "'\n");
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
			return file;
		}
		return Files.writeString(directory.resolve("claude-fixture.cmd"), "@echo off\r\ntype nul > \"" + marker +
			"\"\r\n");
	}

	/**
	 * Returns the authentication of a source home whose settings hold only an API key.
	 *
	 * @param root  the folder that receives the source home
	 * @param name  the source home's name
	 * @param token the API key
	 * @return the authentication
	 * @throws IOException if the source home cannot be written or read
	 */
	private static ClaudeAuthentication apiKeySource(Path root, String name, String token) throws IOException
	{
		Path source = SourceHomes.create(root, name);
		SourceHomes.settings(source, """
			{"env": {"ANTHROPIC_API_KEY": "%s"}}
			""".formatted(token));
		return ClaudeAuthentication.read(JSON, named(source, null), Map.of(), Platform.current(), SourceHomes.PLUGIN);
	}

	/**
	 * Returns the environment of a session's Claude process whose credential comes from a source home, for a runner
	 * whose own home folder holds no Claude authentication.
	 *
	 * @param scope    the runner's scope
	 * @param root     the test's folder
	 * @param source   the source home
	 * @param declared the class of login that the launch declares, or {@code null} for none
	 * @return the environment
	 * @throws IOException if the source home or the environment cannot be read
	 */
	private static Map<String, String> sessionEnvironment(HarnessRunnerScope scope, Path root, Path source,
		String declared) throws IOException
	{
		Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
			SourceHomes.create(root, "runner").toString());
		ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(source, declared), environment,
			Platform.current(), SourceHomes.PLUGIN);
		return ClaudeRunner.processEnvironment(scope, environment, HarnessDependencyCaches.NONE,
			root.resolve("missing-settings.json"), root.resolve("session-home"), null, authentication);
	}

	/**
	 * Returns the choice of a launch that names a source home.
	 *
	 * @param source   the source home
	 * @param declared the class of login that the launch declares, or {@code null} for none
	 * @return the choice
	 */
	private static AuthenticationOptions named(Path source, String declared)
	{
		Optional<HostLogin> loginClass = Optional.empty();
		if (declared != null)
			loginClass = Optional.of(AuthenticationOptions.parseLoginClass(declared));
		return new AuthenticationOptions(Optional.of(source), loginClass, false);
	}
}
