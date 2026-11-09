package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.installer.Integration;
import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.runner.ClaudeProcess;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
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
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.SetupCommands;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.testng.Assert.expectThrows;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
	 * file sets the marker's variable otherwise, so that the session's commands send their sessions to the run's runner.
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
	 * Verifies that a Claude child needs the model host that the caller's settings name and the shared caches, and
	 * no login file, even though the caller's Claude configuration directory holds one: the child copies none.
	 *
	 * @throws IOException if the fixture or the report cannot be built
	 */
	@Test
	public void childNeedsModelHostAndCachesButNoLogin() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-child-needs-")).toRealPath();
			Path configuration = Files.createDirectory(home.resolve(".claude"));
			Files.writeString(configuration.resolve("settings.json"), """
				{"env": {"ANTHROPIC_BASE_URL": "https://Gateway.Example:8443/v1"}}
				""");
			Files.writeString(configuration.resolve(".credentials.json"), "{}");
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString());

			ChildNeedsReport needs = ClaudeRunner.childNeeds(scope, environment);

			requireThat(needs.hosts(), "hosts").isEqualTo(Set.of("gateway.example"));
			requireThat(needs.writes(), "writes").isEqualTo(List.of(home.resolve(".npm"), home.resolve(".m2")));
			requireThat(needs.logins(), "logins").isEmpty();
		}
	}

	/**
	 * Verifies that a Claude child whose caller's environment holds an authentication variable needs no login file.
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
				"ANTHROPIC_API_KEY", "fake-api-key");

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
	 * Verifies that each Claude authentication variable alone lets a run start, even though neither the user's Claude
	 * configuration directory nor the macOS Keychain holds a login, and that the runner then asks the Keychain
	 * nothing, because the session authenticates through the variable.
	 *
	 * @param variable the authentication variable
	 * @throws IOException if the fixture cannot be built
	 */
	@Test(dataProvider = "authenticationVariables")
	public void acceptsEachLoginVariable(String variable) throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.NOT_FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();

			ClaudeRunner.requireLogin(jvm, Map.of("PATH", "", Platform.MACOS_ARM64.homeVariable(), home.toString(),
				variable, "fake-token"), Platform.MACOS_ARM64);

			requireThat(keychain.commands(), "commands").isEmpty();
		}
	}

	/**
	 * Verifies that a variable that the caller's Claude settings file sets in its {@code env} lets a run start, as
	 * the session receives it from there.
	 *
	 * @throws IOException if the fixture cannot be built
	 */
	@Test
	public void acceptsLoginVariableFromSettings() throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.NOT_FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();
			Files.writeString(Files.createDirectory(home.resolve(".claude")).resolve("settings.json"), """
				{"env": {"CLAUDE_CODE_OAUTH_TOKEN": "fake-token"}}
				""");

			ClaudeRunner.requireLogin(jvm, Map.of("PATH", "", Platform.MACOS_ARM64.homeVariable(), home.toString()),
				Platform.MACOS_ARM64);

			requireThat(keychain.commands(), "commands").isEmpty();
		}
	}

	/**
	 * Verifies that a run on Linux or macOS is refused, with a reason that tells the user to log in to Claude Code
	 * with {@code /login} and names the user's Claude configuration directory, when no authentication variable holds
	 * a value and the user's login store holds no login: the configuration directory holds no
	 * {@code .credentials.json} and, on macOS, the Keychain holds no login item. The directory is the one that
	 * {@code CLAUDE_CONFIG_DIR} names when it is set, even though {@code .claude} in the home directory then holds a
	 * login file.
	 *
	 * @param platform    the platform whose login store the runner checks
	 * @param configured  whether the caller's environment names the configuration directory in
	 *                    {@code CLAUDE_CONFIG_DIR}
	 * @param environment the rest of the caller's environment, without a usable authentication variable
	 * @throws IOException if the fixture cannot be built
	 */
	@Test(dataProvider = "environmentsWithoutLogin")
	public void refusesRunWithoutLogin(Platform platform, boolean configured, Map<String, String> environment)
		throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.NOT_FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();
			Path configuration = Files.createDirectory(home.resolve(".claude"));
			Map<String, String> caller = new HashMap<>(environment);
			caller.put(platform.homeVariable(), home.toString());
			if (configured)
			{
				Files.writeString(configuration.resolve(".credentials.json"), "{}");
				configuration = Files.createDirectory(home.resolve("claude-config"));
				caller.put("CLAUDE_CONFIG_DIR", configuration.toString());
			}
			Path loginDirectory = configuration;

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () ->
				ClaudeRunner.requireLogin(jvm, caller, platform));

			requireThat(refusal.getMessage(), "message").contains("/login").contains(loginDirectory.toString()).
				doesNotContain("setup-token");
		}
	}

	/**
	 * Verifies that a run on Linux or macOS is refused, naming the directory of the user's login store, when the
	 * caller's {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} selects a login store that holds no login, although
	 * {@code CLAUDE_CONFIG_DIR} names a directory that holds a login file: the store is the directory that the
	 * variable names, or, when its value is empty, {@code .claude} in the home directory, whose Keychain item on
	 * macOS has no hash in its name. The user's own Claude Code uses that store, and so does the session's.
	 *
	 * @param platform the platform whose login store the runner checks
	 * @param named    whether the caller's {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names a directory, rather than
	 *                 being empty
	 * @throws IOException if the fixture cannot be built
	 */
	@Test(dataProvider = "loginStorePlatforms")
	public void refusesRunWithoutLoginInUserLoginStore(Platform platform, boolean named) throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.NOT_FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();
			Path defaultDirectory = Files.createDirectory(home.resolve(".claude"));
			Path configured = Files.createDirectory(home.resolve("claude-config"));
			Files.writeString(configured.resolve(".credentials.json"), "{}");
			Map<String, String> caller = new HashMap<>();
			caller.put("PATH", "");
			caller.put("USER", "alice");
			caller.put(platform.homeVariable(), home.toString());
			caller.put("CLAUDE_CONFIG_DIR", configured.toString());
			Path loginDirectory = defaultDirectory;
			if (named)
			{
				Files.writeString(defaultDirectory.resolve(".credentials.json"), "{}");
				loginDirectory = Files.createDirectory(home.resolve("login-store"));
				caller.put("CLAUDE_SECURESTORAGE_CONFIG_DIR", loginDirectory.toString());
			}
			else
				caller.put("CLAUDE_SECURESTORAGE_CONFIG_DIR", "");
			Path expectedDirectory = loginDirectory;

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () ->
				ClaudeRunner.requireLogin(jvm, caller, platform));

			requireThat(refusal.getMessage(), "message").contains("/login").contains(expectedDirectory.toString());
			int lookups = 0;
			if (platform == Platform.MACOS_ARM64)
				lookups = 1;
			requireThat(keychain.commands().size(), "lookups").isEqualTo(lookups);
		}
	}

	/**
	 * Verifies that a run starts without an authentication variable when the directory that the caller's
	 * {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names holds Claude Code's login file, although neither the directory
	 * that {@code CLAUDE_CONFIG_DIR} names nor {@code .claude} in the home directory holds one, because the user's
	 * Claude Code and the session's use the login there.
	 *
	 * @param platform the platform whose login store the runner checks
	 * @throws IOException if the fixture cannot be built
	 */
	@Test(dataProvider = "fileLoginPlatforms")
	public void acceptsLoginFileInUserLoginStore(Platform platform) throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.NOT_FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();
			Files.createDirectory(home.resolve(".claude"));
			Path configured = Files.createDirectory(home.resolve("claude-config"));
			Path store = Files.createDirectory(home.resolve("login-store"));
			Files.writeString(store.resolve(".credentials.json"), "{}");

			ClaudeRunner.requireLogin(jvm, Map.of("PATH", "", platform.homeVariable(), home.toString(),
				"CLAUDE_CONFIG_DIR", configured.toString(), "CLAUDE_SECURESTORAGE_CONFIG_DIR", store.toString()),
				platform);

			requireThat(keychain.commands(), "commands").isEmpty();
		}
	}

	/**
	 * Verifies that a run on Windows starts without an authentication variable although the user's Claude
	 * configuration directory holds no login file, because Claude Code may keep the login in the Windows Credential
	 * Manager, so the runner cannot tell that no login exists.
	 *
	 * @param configured whether the caller's environment names the configuration directory in
	 *                   {@code CLAUDE_CONFIG_DIR}
	 * @throws IOException if the fixture cannot be built
	 */
	@Test(dataProvider = "configurationChoices")
	public void startsRunOnWindowsWithoutLoginFile(boolean configured) throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.NOT_FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();
			Map<String, String> caller = new HashMap<>();
			caller.put("PATH", "");
			caller.put(Platform.WINDOWS_X64.homeVariable(), home.toString());
			Files.createDirectory(home.resolve(".claude"));
			if (configured)
				caller.put("CLAUDE_CONFIG_DIR", Files.createDirectory(home.resolve("claude-config")).toString());

			ClaudeRunner.requireLogin(jvm, caller, Platform.WINDOWS_X64);

			requireThat(keychain.commands(), "commands").isEmpty();
		}
	}

	/**
	 * Verifies that a run starts without an authentication variable when the user's Claude configuration directory
	 * holds Claude Code's login file, {@code .credentials.json}, whether the directory is {@code .claude} in the
	 * home directory or the one that {@code CLAUDE_CONFIG_DIR} names, because the session's Claude Code uses the
	 * user's login there.
	 *
	 * @param platform   the platform whose login store the runner checks
	 * @param configured whether the caller's environment names the configuration directory in
	 *                   {@code CLAUDE_CONFIG_DIR}
	 * @throws IOException if the fixture cannot be built
	 */
	@Test(dataProvider = "loginDirectories")
	public void acceptsUserLoginFile(Platform platform, boolean configured) throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.NOT_FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();
			Map<String, String> caller = new HashMap<>();
			caller.put("PATH", "");
			caller.put(platform.homeVariable(), home.toString());
			Path configuration = home.resolve(".claude");
			if (configured)
			{
				configuration = home.resolve("claude-config");
				caller.put("CLAUDE_CONFIG_DIR", configuration.toString());
			}
			Files.writeString(Files.createDirectory(configuration).resolve(".credentials.json"), "{}");

			ClaudeRunner.requireLogin(jvm, caller, platform);
		}
	}

	/**
	 * Verifies that on macOS a run starts without an authentication variable or login file when the Keychain holds
	 * the user's Claude Code login item, and that the runner only asks whether the item exists, without {@code -w},
	 * so that the Keychain reveals no secret: the item's account is the user's name, and its service is
	 * {@code Claude Code-credentials}, followed, when a variable names the directory of the login store, by
	 * {@code -} and the first eight hexadecimal digits of the SHA-256 hash of that directory in Unicode normalization
	 * form C. The caller's {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names the store, or, when it is unset,
	 * {@code CLAUDE_CONFIG_DIR} does; an empty {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names none.
	 *
	 * @param configured the caller's value of {@code CLAUDE_CONFIG_DIR}, or an empty string if it sets none
	 * @param store      the caller's value of {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}, or {@code null} if it sets
	 *                   none
	 * @param service    the Keychain service of the user's login item
	 * @throws IOException if the fixture cannot be built
	 */
	@Test(dataProvider = "keychainServices")
	public void acceptsKeychainLogin(String configured, String store, String service) throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();
			Map<String, String> caller = new HashMap<>();
			caller.put("PATH", "");
			caller.put("HOME", home.toString());
			caller.put("USER", "alice");
			if (!configured.isEmpty())
				caller.put("CLAUDE_CONFIG_DIR", configured);
			if (store != null)
				caller.put("CLAUDE_SECURESTORAGE_CONFIG_DIR", store);

			ClaudeRunner.requireLogin(jvm, caller, Platform.MACOS_ARM64);

			requireThat(keychain.commands(), "commands").isEqualTo(List.of(List.of("/usr/bin/security",
				"find-generic-password", "-a", "alice", "-s", service)));
		}
	}

	/**
	 * Verifies that on macOS the runner looks up the Keychain item of a user whose name Claude Code does not use as
	 * an account, because it holds a character other than an ASCII letter, a digit, {@code .}, {@code _} or
	 * {@code -}, under the account {@code claude-code-user}, as Claude Code stores it.
	 *
	 * @throws IOException if the fixture cannot be built
	 */
	@Test
	public void looksUpKeychainLoginOfUnusualUserName() throws IOException
	{
		FakeKeychain keychain = new FakeKeychain(FakeKeychain.FOUND);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();

			ClaudeRunner.requireLogin(jvm, Map.of("PATH", "", "HOME", home.toString(), "USER", "Alice Smith"),
				Platform.MACOS_ARM64);

			requireThat(keychain.commands(), "commands").isEqualTo(List.of(List.of("/usr/bin/security",
				"find-generic-password", "-a", "claude-code-user", "-s", "Claude Code-credentials")));
		}
	}

	/**
	 * Verifies that on macOS a run starts when the Keychain answers neither that it holds the login item nor that it
	 * holds none, as when the Keychain is locked: the runner refuses a run only when it knows that there is no login,
	 * and the session's Claude Code then reports what keeps it from the login.
	 *
	 * @throws IOException if the fixture cannot be built
	 */
	@Test
	public void startsRunWhenKeychainCannotTell() throws IOException
	{
		// errSecInteractionNotAllowed, which security reports while the Keychain is locked
		FakeKeychain keychain = new FakeKeychain(36);
		try (TestJvmScope build = new TestJvmScope();
			TestJvmScope jvm = keychain.scope(build.temporaryDirectory());
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-claude-login-")).
				toRealPath();

			ClaudeRunner.requireLogin(jvm, Map.of("PATH", "", "HOME", home.toString(), "USER", "alice"),
				Platform.MACOS_ARM64);

			requireThat(keychain.commands().size(), "commands").isEqualTo(1);
		}
	}

	/**
	 * Returns the variables that each authenticate a Claude session.
	 *
	 * @return the variable names
	 */
	@DataProvider
	public static Object[][] authenticationVariables()
	{
		return new Object[][]
		{
			{"CLAUDE_CODE_OAUTH_TOKEN"},
			{"ANTHROPIC_API_KEY"},
			{"ANTHROPIC_AUTH_TOKEN"}
		};
	}

	/**
	 * Returns platforms, whether the caller names its Claude configuration directory, and caller environments that
	 * hold no usable authentication variable.
	 *
	 * @return the platforms, choices and environments
	 */
	@DataProvider
	public static Object[][] environmentsWithoutLogin()
	{
		Map<String, String> blank = Map.of("PATH", "", "CLAUDE_CODE_OAUTH_TOKEN", "", "ANTHROPIC_API_KEY", " ");
		return new Object[][]
		{
			{Platform.LINUX_X64, false, Map.of("PATH", "")},
			{Platform.LINUX_X64, false, blank},
			{Platform.LINUX_X64, true, Map.of("PATH", "")},
			{Platform.MACOS_ARM64, false, Map.of("PATH", "", "USER", "alice")},
			{Platform.MACOS_ARM64, true, blank}
		};
	}

	/**
	 * Returns platforms and whether the caller names its Claude configuration directory.
	 *
	 * @return the platforms and choices
	 */
	@DataProvider
	public static Object[][] loginDirectories()
	{
		return new Object[][]
		{
			{Platform.LINUX_X64, false},
			{Platform.LINUX_X64, true},
			{Platform.WINDOWS_X64, false},
			{Platform.WINDOWS_X64, true},
			{Platform.MACOS_ARM64, false},
			{Platform.MACOS_ARM64, true}
		};
	}

	/**
	 * Returns values of {@code CLAUDE_CONFIG_DIR} and {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} and the Keychain
	 * service of the login item that Claude Code stores for each, including the same directory name in Unicode
	 * normalization forms D and C.
	 *
	 * @return the values and services
	 */
	@DataProvider
	public static Object[][] keychainServices()
	{
		return new Object[][]
		{
			{"", null, "Claude Code-credentials"},
			{"/Users/alice/claude-config", null, "Claude Code-credentials-1ab0976d"},
			{"/Users/alice/claude-café", null, "Claude Code-credentials-347c1370"},
			{"/Users/alice/claude-café", null, "Claude Code-credentials-347c1370"},
			{"/Users/alice/claude-config", "", "Claude Code-credentials"},
			{"", "/Users/alice/claude-config", "Claude Code-credentials-1ab0976d"},
			{"/Users/alice/other-config", "/Users/alice/claude-config", "Claude Code-credentials-1ab0976d"}
		};
	}

	/**
	 * Returns the platforms whose runner refuses a run that it knows to have no login, and whether the caller's
	 * {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names a directory.
	 *
	 * @return the platforms and choices
	 */
	@DataProvider
	public static Object[][] loginStorePlatforms()
	{
		return new Object[][]
		{
			{Platform.LINUX_X64, true},
			{Platform.LINUX_X64, false},
			{Platform.MACOS_ARM64, true},
			{Platform.MACOS_ARM64, false}
		};
	}

	/**
	 * Returns the platforms.
	 *
	 * @return the platforms
	 */
	@DataProvider
	public static Object[][] fileLoginPlatforms()
	{
		return new Object[][]
		{
			{Platform.LINUX_X64},
			{Platform.WINDOWS_X64},
			{Platform.MACOS_ARM64}
		};
	}

	/**
	 * Returns whether the caller's environment names its Claude configuration directory.
	 *
	 * @return the choices
	 */
	@DataProvider
	public static Object[][] configurationChoices()
	{
		return new Object[][]
		{
			{false},
			{true}
		};
	}

	/**
	 * Verifies that the Claude process environment points the session's Claude Code at the login store of the user's
	 * own Claude Code, through {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}, while its configuration directory stays the
	 * run's own: the store is the directory that the caller's non-empty {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names,
	 * or else, when that variable is unset, the one that its {@code CLAUDE_CONFIG_DIR} names, each as written there;
	 * otherwise it is the default store, which on macOS an empty value selects, because only that value selects the
	 * Keychain item of the user's default store, and elsewhere {@code .claude} in the caller's home directory. The
	 * session therefore uses and refreshes the user's own Claude login, and its settings stay its own.
	 *
	 * @param configured the caller's value of {@code CLAUDE_CONFIG_DIR} below the fixture's folder, or an empty
	 *                   string if it sets none
	 * @param store      the caller's value of {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}, below the fixture's folder
	 *                   unless it is empty, or {@code null} if it sets none
	 * @param expected   the session's value of {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} below the fixture's folder, or
	 *                   {@code null} for the default store
	 * @throws IOException if the environment cannot be built
	 */
	@Test(dataProvider = "userConfigurations")
	public void sharesUserLoginWithSession(String configured, String store, String expected) throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-shared-login-")).toRealPath();
			Path userHome = Files.createDirectory(root.resolve("user"));
			Path runHome = Files.createDirectory(root.resolve("run-home"));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");
			environment.put(Platform.current().homeVariable(), userHome.toString());
			if (!configured.isEmpty())
				environment.put("CLAUDE_CONFIG_DIR", root + configured);
			if (store != null && store.isEmpty())
				environment.put("CLAUDE_SECURESTORAGE_CONFIG_DIR", "");
			else if (store != null)
				environment.put("CLAUDE_SECURESTORAGE_CONFIG_DIR", root + store);
			String loginStore;
			if (expected != null)
				loginStore = root + expected;
			else if (Platform.current().isLinux() || Platform.current().isWindows())
				loginStore = userHome.resolve(".claude").toString();
			else
				loginStore = "";

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, root.resolve("missing-settings.json"), runHome, null);

			requireThat(result.get("CLAUDE_SECURESTORAGE_CONFIG_DIR"), "loginStore").isEqualTo(loginStore);
			requireThat(result.get("CLAUDE_CONFIG_DIR"), "configurationDirectory").
				isEqualTo(runHome.resolve(".claude").toString());
		}
	}

	/**
	 * Verifies that the Claude process environment states the class of the user's login in {@code CAT_HOST_LOGIN},
	 * because the session's commands, among them the prompt optimizer, cannot read the login file: {@code subscription}
	 * when the login store holds a subscription login, {@code api_key} when the environment selects an API key, and
	 * {@code unknown} when there is no login. The caller's own {@code CAT_HOST_LOGIN} decides nothing.
	 *
	 * @param login       what the user has: {@code "subscription"}, {@code "api key"}, or {@code "none"}
	 * @param callerClaim the caller's value of {@code CAT_HOST_LOGIN}, or {@code null} if it sets none
	 * @param expected    the session's value of {@code CAT_HOST_LOGIN}
	 * @throws IOException if the environment cannot be built
	 */
	@Test(dataProvider = "hostLogins")
	public void statesTheLoginClassToTheSession(String login, String callerClaim, String expected)
		throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-login-class-")).toRealPath();
			Path store = Files.createDirectory(root.resolve("store"));
			Path runHome = Files.createDirectory(root.resolve("run-home"));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");
			environment.put(Platform.current().homeVariable(), root.resolve("user").toString());
			environment.put("CLAUDE_SECURESTORAGE_CONFIG_DIR", store.toString());
			if (login.equals("subscription"))
			{
				Files.writeString(store.resolve(".credentials.json"),
					"{\"claudeAiOauth\":{\"accessToken\":\"placeholder\"}}");
			}
			else if (login.equals("api key"))
				environment.put("ANTHROPIC_API_KEY", "placeholder");
			if (callerClaim != null)
				environment.put("CAT_HOST_LOGIN", callerClaim);

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, root.resolve("missing-settings.json"), runHome, null);

			requireThat(result.get("CAT_HOST_LOGIN"), "hostLogin").isEqualTo(expected);
		}
	}

	/**
	 * Returns what the user has, the caller's own {@code CAT_HOST_LOGIN}, and the value that the session receives.
	 *
	 * @return the values
	 */
	@DataProvider
	public static Object[][] hostLogins()
	{
		return new Object[][]
		{
			{"subscription", null, "subscription"},
			{"api key", null, "api_key"},
			{"none", null, "unknown"},
			{"subscription", "api_key", "subscription"},
			{"none", "subscription", "unknown"}
		};
	}

	/**
	 * Verifies that the Claude process environment names a login store that the caller's
	 * {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names by a relative path as an absolute path, because the session's
	 * Claude Code runs in another working directory.
	 *
	 * @throws IOException if the environment cannot be built
	 */
	@Test
	public void namesRelativeLoginStoreAbsolutely() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-shared-login-")).toRealPath();
			Path runHome = Files.createDirectory(root.resolve("run-home"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(),
				root.resolve("user").toString(), "CLAUDE_SECURESTORAGE_CONFIG_DIR", "login-store");

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, root.resolve("missing-settings.json"), runHome, null);

			Path loginStore = Path.of(result.get("CLAUDE_SECURESTORAGE_CONFIG_DIR"));
			requireThat(loginStore.isAbsolute(), "loginStore.isAbsolute()").isTrue();
			requireThat(loginStore.getFileName().toString(), "loginStore.getFileName()").isEqualTo("login-store");
		}
	}

	/**
	 * Returns values of {@code CLAUDE_CONFIG_DIR} and {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} below a folder,
	 * including one that ends in a name separator, and the session's value of
	 * {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} below the folder, or {@code null} for the default store.
	 *
	 * @return the values
	 */
	@DataProvider
	public static Object[][] userConfigurations()
	{
		String configured = File.separator + "claude-config";
		String store = File.separator + "login-store";
		return new Object[][]
		{
			{"", null, null},
			{configured, null, configured},
			{configured + File.separator, null, configured + File.separator},
			{"", store, store},
			{configured, store, store},
			{configured, "", null}
		};
	}

	/**
	 * Verifies that the commands of a session cannot read the user's Claude login file, in {@code .claude} in the
	 * user's home directory, in the directory that {@code CLAUDE_CONFIG_DIR} names or in the one that
	 * {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names, although the session's Claude Code uses the login there: the
	 * session's sandbox policy denies reading each file and lets the commands neither write nor read again any
	 * directory that holds them.
	 *
	 * @throws IOException if the run or its settings cannot be created
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
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"),
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("claude");
			manager.snapshotProject(run);
			Path project = Files.createDirectories(run.directory().resolve("project/.git")).getParent();
			List<Path> deniedReads = ClaudeRunner.sessionDeniedReads(manager, run, List.of(), List.of(), environment);

			ChildPolicy policy = ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"),
				run.directory().resolve("home/.claude/settings.json"), run.directory().resolve("claude"),
				run.directory().resolve("home"), List.of(), run.directory(), deniedReads, "", null, project,
				run.directory(), null, ExtraPermissions.NONE, "api.example");

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
	 * Verifies that the Claude process environment passes the OAuth token to the session, so that the session and
	 * the sessions that its commands request authenticate through it.
	 *
	 * @throws IOException if the environment cannot be built
	 */
	@Test
	public void passesOauthTokenToSession() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-oauth-token-"));
			Map<String, String> environment = new HashMap<>();
			environment.put("PATH", "");
			environment.put("CLAUDE_CODE_OAUTH_TOKEN", "fake-token");

			Map<String, String> result = ClaudeRunner.processEnvironment(scope, environment,
				HarnessDependencyCaches.NONE, home.resolve("missing-settings.json"), home, null);

			requireThat(result.get("CLAUDE_CODE_OAUTH_TOKEN"), "token").isEqualTo("fake-token");
		}
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
	 * Verifies that a session that may request sessions keeps its transcript, which a runner launcher that the
	 * session's commands run reads to find the session's sandbox policy, while a session that may request none still
	 * keeps no session.
	 */
	@Test
	public void persistsSessionThatMayRequestSessions()
	{
		List<String> mayRequest = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of(), false, 2);
		List<String> mayRequestNone = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(),
			Path.of("hook-results"), List.of(), false, 0);

		requireThat(mayRequest, "mayRequest").doesNotContain("--no-session-persistence");
		requireThat(mayRequestNone, "mayRequestNone").contains("--no-session-persistence");
	}

	/**
	 * Verifies that the session runs in the {@code acceptEdits} permission mode, in which the sandbox confines its
	 * commands and its edits stay inside the project.
	 */
	@Test
	public void selectsAcceptEditsPermissionMode()
	{
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of(), false, 0);

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
	 * Verifies that a session that may request sessions gets the same system prompt as one that may request none: the
	 * command adds no instructions on requesting a session, which the session reads in CAT's rules instead.
	 */
	@Test
	public void addsNoRequestInstructions()
	{
		// Requirement: R-I9
		List<String> command = ClaudeRunner.command("claude", "sonnet", "low", "", List.of(), Path.of("hook-results"),
			List.of(), false, 2);

		requireThat(command, "command").doesNotContain("--append-system-prompt").
			doesNotContain("--append-system-prompt-file").doesNotContain("--system-prompt").
			doesNotContain("--system-prompt-file");
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
			Map<String, List<String>> entries = new Integration().permissions(installer);
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
	 * records name, beside the copy in the run's marketplace.
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
				expected.addAll(integration.permissions(plugin.resolve(PluginDirectory.INSTALLER)).
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
		return ClaudeRunner.installPlugins("claude", artifact, installCat, trialPlugins, configuration,
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
	 * besides the paths that the caller denies, and any login file in its home. The session can still read its project,
	 * the rest of its home, its Claude temporary directory and the evidence that it validates.
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
				evidence.directory().resolve(".lock"), workspaces, directory.resolve("home/.claude/settings.json"),
				directory.resolve("home/.claude/.credentials.json")));
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
	 * Verifies that a session that its requester's sandbox policy bounds starts although its run lies in the
	 * requester's requested-runs directory, which that policy does not let the requester write: the first requested
	 * session's policy, which holds only its home, requests directory, worktree registrations and dependency cache,
	 * bounds the session that it requests, and the settings of that session let its commands write its own home, the
	 * cache, its requests directory and its worktree registrations, but neither its whole run folder nor its responses
	 * directory.
	 *
	 * @throws IOException if the runs cannot be created
	 */
	@Test
	public void startsNestedRequestedSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-nested-run-")).toRealPath();
			Path artifact = root.resolve("artifact");
			Path cache = Files.createDirectories(root.resolve("cache"));
			HarnessRunManager firstManager = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"),
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun first = firstManager.create("claude");
			SessionRequestAccess firstAccess = SessionRequestAccess.allowing(first, artifact, 1, Platform.current(),
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			Path firstProject = Files.createDirectories(first.directory().resolve("project/.git")).getParent();
			ChildPolicy firstPolicy = ClaudeConnectionSettings.writeIsolatedSettings(scope,
				root.resolve("missing.json"), first.directory().resolve("home/.claude/settings.json"),
				first.directory().resolve("claude"), first.directory().resolve("home"), List.of(cache),
				first.directory(), List.of(), "", firstAccess, firstProject, first.directory(), null,
				ExtraPermissions.NONE, "api.example");
			HarnessRunManager nestedManager = new HarnessRunManager(jvm.fileLocks(), firstAccess.requestedRunRoot(1),
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun nested = nestedManager.create("claude");
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
				cache.toString(), nestedAccess.requests().toString(),
				nestedProject.resolve(".git/worktrees").toString()));
			requireThat(nestedPolicy.writeRoots(), "writeRoots").doesNotContain(nested.directory());
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
}
