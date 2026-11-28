package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.MissingPermissionsException;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests {@link ClaudeConnectionSettings}.
 */
public final class ClaudeConnectionSettingsTest
{
	/**
	 * Verifies that requested sessions grant the literal launcher commands, the request launcher and both runner
	 * launchers beside it, and no launcher grant otherwise, beside the grant of the commands that the sandbox confines.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void permitsSessionRequestLauncher() throws IOException
	{
		// Requirement: R-I9, R-I11
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-request-permissions-"));
			Path destination = root.resolve("isolated/settings.json");
			Path launcher = root.resolve("artifact with spaces/bin/request-session");
			SessionRequestAccess access = new SessionRequestAccess(root.resolve("requests"), root.resolve("responses"),
				root.resolve("requested-results"), launcher, SessionRequestAccess.Shell.POSIX);

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", access, null);
			JsonNode allow = scope.json().readTree(Files.readString(destination)).path("permissions").path("allow");
			requireThat(allow.size(), "grantCount").isEqualTo(8);
			requireThat(allow.path(3).asString(), "launcherGrant").isEqualTo("Bash(" + launcher + ":*)");
			requireThat(allow.path(4).asString(), "claudeRunnerGrant").isEqualTo("Bash(" +
				launcher.resolveSibling("claude-runner") + ":*)");
			requireThat(allow.path(5).asString(), "codexRunnerGrant").isEqualTo("Bash(" +
				launcher.resolveSibling("codex-runner") + ":*)");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", null, null);
			allow = scope.json().readTree(Files.readString(destination)).path("permissions").path("allow");
			requireThat(allow.size(), "defaultGrantCount").isEqualTo(4);
			requireThat(allow.path(0).asString(), "skillGrant").isEqualTo("Skill");
			requireThat(allow.path(1).asString(), "editGrant").isEqualTo("Edit(./**)");
		}
	}

	/**
	 * Grants response reads without granting response writes, and preserves settings when requests are disabled.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void permitsOnlyResponseReads() throws IOException
	{
		// Requirement: R-I9
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-response-permissions-")).toRealPath();
			Path destination = root.resolve("isolated/settings.json");
			Path launcher = root.resolve("artifact with spaces/bin/request-session");
			Path responses = root.resolve("responses");
			SessionRequestAccess access = new SessionRequestAccess(root.resolve("requests"), responses,
				root.resolve("requested-results"), launcher, SessionRequestAccess.Shell.POSIX);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", null, null);
			String defaultSettings = Files.readString(destination);

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", access, null);
			JsonNode settings = scope.json().readTree(Files.readString(destination));
			List<String> grants = new ArrayList<>();
			for (JsonNode grant : settings.path("permissions").path("allow"))
				grants.add(grant.asString());
			requireThat(grants, "grants").isEqualTo(List.of("Skill", "Edit(./**)",
				"Read(/" + ClaudeConnectionSettings.rulePath(NestedSessionManager.linkTargets(root)) + "/**)",
				"Bash(" + launcher + ":*)",
				"Bash(" + launcher.resolveSibling("claude-runner") + ":*)",
				"Bash(" + launcher.resolveSibling("codex-runner") + ":*)",
				"Read(/" + ClaudeConnectionSettings.rulePath(responses) + "/**)", "Bash"));
			requireThat(settings.path("permissions").has("additionalDirectories"), "additionalDirectories").isFalse();
			List<String> writablePaths = new ArrayList<>();
			for (JsonNode path : settings.path("sandbox").path("filesystem").path("allowWrite"))
			{
				writablePaths.add(path.asString());
				requireThat(responses.startsWith(Path.of(path.asString())), "writableResponses").isFalse();
			}
			requireThat(writablePaths, "writablePaths").isEqualTo(List.of(root.resolve("home").toString(),
				access.requests().toString()));

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", null, null);
			requireThat(Files.readString(destination), "defaultSettings").isEqualTo(defaultSettings);
		}
	}

	/**
	 * Grants reads of the nested session's {@code link-targets} directory, which the project's links to targets outside
	 * it lead to, and grants no write of it: no sandbox write path or edit rule lets the session write there.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void permitsOnlyLinkTargetReads() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-link-target-permissions-")).toRealPath();
			Path destination = root.resolve("isolated/settings.json");
			Path linkTargets = NestedSessionManager.linkTargets(root);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", null, null);
			JsonNode settings = scope.json().readTree(Files.readString(destination));

			List<String> grants = new ArrayList<>();
			for (JsonNode grant : settings.path("permissions").path("allow"))
				grants.add(grant.asString());
			requireThat(grants, "grants").contains("Read(/" + ClaudeConnectionSettings.rulePath(linkTargets) + "/**)");
			for (String grant : grants)
			{
				if (grant.startsWith("Edit(") || grant.startsWith("Write("))
					requireThat(grant, "writeGrant").doesNotContain("link-targets");
			}
			for (JsonNode path : settings.path("sandbox").path("filesystem").path("allowWrite"))
			{
				Path writable = Path.of(path.asString());
				requireThat(linkTargets.startsWith(writable) || writable.startsWith(linkTargets), "writable").
					withContext(writable, "path").isFalse();
			}
			for (JsonNode path : settings.path("sandbox").path("filesystem").path("denyRead"))
				requireThat(linkTargets.startsWith(Path.of(path.asString())), "denied").isFalse();
		}
	}

	/**
	 * Verifies that the parent process's Claude credential variables and configuration directory reach no nested
	 * Claude process, whose credential comes from its source home, while the parent's subagent settings do.
	 */
	@Test
	public void copiesNoProcessCredential()
	{
		// Requirement: R-I14
		Map<String, String> environment = new HashMap<>();
		ClaudeConnectionSettings.copyProcessEnvironment(Map.of(
			"ANTHROPIC_BASE_URL", "https://process.example",
			"ANTHROPIC_AUTH_TOKEN", "process-token",
			"CLAUDE_CONFIG_DIR", "/host/configuration",
			"CLAUDE_CODE_SUBAGENT_MODEL", "haiku"), environment);
		requireThat(environment, "environment").isEqualTo(Map.of("CLAUDE_CODE_SUBAGENT_MODEL", "haiku"));
	}

	/**
	 * Verifies that host settings add only the missing subagent settings, and none of their credential variables or
	 * unrelated settings.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesOnlyMissingSubagentSettings() throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path settings = cleanup.add(Files.createTempFile("cat-claude-settings-", ".json"));
			Files.writeString(settings, """
				{
				  "env": {
				    "ANTHROPIC_AUTH_TOKEN": "settings-token",
				    "ANTHROPIC_BASE_URL": "https://gateway.example",
				    "CLAUDE_CODE_SUBAGENT_MODEL": "settings-model",
				    "CLAUDE_CODE_SUBAGENT_EFFORT": "low",
				    "UNRELATED_SETTING": "must-not-copy"
				  }
				}
				""");
			Map<String, String> environment = new HashMap<>(Map.of("CLAUDE_CODE_SUBAGENT_MODEL", "process-model"));
			ClaudeConnectionSettings.copy(scope.json(), settings, environment);
			requireThat(environment, "environment").isEqualTo(Map.of("CLAUDE_CODE_SUBAGENT_MODEL", "process-model",
				"CLAUDE_CODE_SUBAGENT_EFFORT", "low"));
		}
	}

	/**
	 * Verifies isolated settings enforce runner-owned safety flags, retain the rules that deny or ask for the parent's
	 * permission but none of the parent's sandbox settings, and set CAT-owned Claude environment values. They do not
	 * retain the parent's grants: its allow rules, its added directories and its default permission mode, which would
	 * let the isolated instance read and write the parent's directories, such as the project whose prompts CAT
	 * measures. They allow the {@code Skill} tool, which a nested instance has no user to approve, and edits inside the
	 * project, which they grant by a rule that does not depend on the permission mode, and the commands that the
	 * sandbox confines. They do not retain the parent's writable paths, such as its Claude and CAT directories, and
	 * allow writes only to the instance's home and the shared dependency caches.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void writesIsolatedSettings() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-network-policy-")).toRealPath();
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Files.writeString(source, """
				{
				  "env": {
				    "DISABLE_AUTOUPDATER": "0",
				    "CLAUDE_CODE_TMPDIR": "untrusted-directory"
				  },
				  "sandbox": {
				    "network": {
				      "allowedDomains": ["registry.npmjs.org"],
				      "deniedDomains": ["blocked.example"]
				    },
				    "filesystem": {
				      "allowWrite": ["/workspace", "/host-home/.claude", "/host-home/.cat"],
				      "denyRead": ["/secrets"]
				    }
				  },
				  "permissions": {
				    "additionalDirectories": ["/workspace"],
				    "allow": ["Bash(*)", "Write(//workspace/**)"],
				    "ask": ["Bash(rm *)"],
				    "defaultMode": "auto",
				    "deny": ["Read(//secrets/**)"]
				  }
				}
				""");

			Path claudeHome = claudeTemporaryDirectory.resolve("home");
			Path npmCache = root.resolve("host-home/.npm");
			Path mavenCache = root.resolve("host-home/.m2");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeHome, List.of(npmCache, mavenCache), claudeTemporaryDirectory, List.of());

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(strings(result.path("sandbox").path("network").path("allowedDomains")), "allowedDomains").
				isEqualTo(List.of("api.anthropic.com"));
			requireThat(result.path("sandbox").path("network").has("deniedDomains"), "deniedDomains").isFalse();
			requireThat(strings(result.path("sandbox").path("filesystem").path("allowWrite")), "allowWrite").
				isEqualTo(List.of(claudeHome.toString(), npmCache.toString(), mavenCache.toString()));
			requireThat(result.path("sandbox").path("filesystem").has("denyRead"), "denyRead").isFalse();
			requireThat(strings(result.path("permissions").path("deny")), "deny").
				isEqualTo(List.of("Read(//secrets/**)"));
			requireThat(strings(result.path("permissions").path("ask")), "ask").isEqualTo(List.of("Bash(rm *)"));
			requireThat(strings(result.path("permissions").path("allow")), "allow").isEqualTo(List.of("Skill", "Edit(./**)",
				"Read(/" + ClaudeConnectionSettings.rulePath(NestedSessionManager.linkTargets(claudeTemporaryDirectory)) +
				"/**)", "Bash"));
			requireThat(result.path("permissions").has("additionalDirectories"), "additionalDirectories").isFalse();
			requireThat(result.path("permissions").has("defaultMode"), "defaultMode").isFalse();
			requireThat(result.path("env").path("DISABLE_AUTOUPDATER").asString(), "autoUpdater").isEqualTo("1");
			requireThat(result.path("env").path("CLAUDE_CODE_TMPDIR").asString(), "temporaryDirectory").
				isEqualTo(claudeTemporaryDirectory.toString());
			requireThat(result.path("sandbox").path("enabled").asBoolean(), "sandboxEnabled").isTrue();
			requireThat(result.path("sandbox").path("failIfUnavailable").asBoolean(), "failIfUnavailable").isTrue();
			requireThat(result.path("sandbox").path("allowUnsandboxedCommands").asBoolean(), "unsandboxedCommands").
			isFalse();
			requireThat(Files.isDirectory(claudeTemporaryDirectory), "claudeTemporaryDirectory").isTrue();
		}
	}

	/**
	 * Verifies that isolated settings let the session's commands reach only the session's model host, Anthropic's API
	 * host by default, while retaining runner-owned safeguards and required environment values.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void limitsNetworkToModelHost() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-no-network-policy-"));
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Files.writeString(source, """
				{"sandbox":{"enabled":true}}
				""");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory, List.of());

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(result.path("env").path("DISABLE_AUTOUPDATER").asString(), "autoUpdater").isEqualTo("1");
			requireThat(result.path("env").path("CLAUDE_CODE_TMPDIR").asString(), "temporaryDirectory").
				isEqualTo(claudeTemporaryDirectory.toString());
			requireThat(result.path("sandbox").path("enabled").asBoolean(), "sandboxEnabled").isTrue();
			requireThat(result.path("sandbox").path("failIfUnavailable").asBoolean(), "failIfUnavailable").isTrue();
			requireThat(result.path("sandbox").path("allowUnsandboxedCommands").asBoolean(), "unsandboxedCommands").
			isFalse();
			requireThat(strings(result.path("sandbox").path("network").path("allowedDomains")), "allowedDomains").
				isEqualTo(List.of("api.anthropic.com"));
		}
	}

	/**
	 * Verifies that the policy that a nested session's runner reports for a session that installs CAT keeps the
	 * installed plugin out of the session's writable paths, and that its sandbox settings deny the commands writing
	 * there, so that a check program that the session's driver starts runs on a runtime that the session cannot
	 * change, and that the rest of the session's home stays writable.
	 *
	 * @throws IOException   if fixture I/O fails
	 * @throws PolicyRefusal if the policy would let the session change the runtime
	 */
	@Test
	public void keepsInstalledPluginUnwritable() throws IOException, PolicyRefusal
	{
		// Requirement: R-L21
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-plugin-policy-")).toRealPath();
			Path home = Files.createDirectories(root.resolve("session/home"));
			Path configuration = Files.createDirectories(home.resolve(".claude"));
			Path plugin = configuration.resolve("plugins/cat-marketplace/plugins/cat");
			Path runtime = Files.createDirectories(plugin.resolve("bin/runtime"));
			Path scratch = Files.createDirectories(home.resolve("scratch"));
			Path destination = configuration.resolve("settings.json");

			Path session = root.resolve("session");
			ChildPolicy policy = ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"),
				destination, session.resolve("claude"), home, List.of(), session, List.of(), "", null, null, session,
				null, ExtraPermissions.NONE, "api.example");

			ParentPolicy owner = policy.asParentPolicy();
			requireThat(owner.mayWrite(runtime), "mayWriteRuntime").isFalse();
			requireThat(owner.mayWrite(scratch), "mayWriteScratch").isTrue();
			owner.requireTrustedTree(runtime);
			List<String> denied = strings(scope.json().readTree(Files.readString(destination)).path("sandbox").
				path("filesystem").path("denyWrite"));
			requireThat(denied, "denyWrite").contains(configuration.resolve("plugins").toString());
		}
	}

	/**
	 * Verifies that the user's allow rules are not copied, and the sandbox is required.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesNoUserAllowRules() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-linux-rules-"));
			Path source = Files.writeString(root.resolve("settings.json"), """
				{"permissions": {"allow": ["Bash(git status:*)"], "deny": ["Bash(git push:*)"]}}
				""");
			Path destination = root.resolve("isolated/settings.json");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, root.resolve("tmp"),
				root.resolve("home"), List.of(), root, List.of());

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(strings(result.path("permissions").path("allow")), "allow").isEqualTo(List.of("Skill", "Edit(./**)",
				"Read(/" + ClaudeConnectionSettings.rulePath(NestedSessionManager.linkTargets(root)) + "/**)", "Bash"));
			requireThat(strings(result.path("permissions").path("deny")), "deny").
				isEqualTo(List.of("Bash(git push:*)"));
			requireThat(result.path("sandbox").path("enabled").asBoolean(), "sandboxEnabled").isTrue();
		}
	}

	/**
	 * Verifies that on Linux, a session's commands that the sandbox confines run without approval, which nobody can
	 * give to a nested instance, while the settings still forbid every command outside the sandbox and keep each
	 * denial: the parent's rules that deny a tool or ask before using it, the listed reads, and writes beyond the
	 * instance's home.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void sandboxedCommandsRunWithoutApproval() throws IOException
	{
		// Requirement: R-I11
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-sandboxed-commands-")).toRealPath();
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path claudeHome = claudeTemporaryDirectory.resolve("home");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			Files.writeString(source, """
				{
				  "sandbox": {
				    "autoAllowBashIfSandboxed": false,
				    "excludedCommands": ["docker *"],
				    "filesystem": {"allowWrite": ["/workspace"], "denyRead": ["/secrets"]}
				  },
				  "permissions": {
				    "ask": ["Bash(git push *)"],
				    "deny": ["Bash(curl *)", "Read(//secrets/**)"]
				  }
				}
				""");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeHome, List.of(), claudeTemporaryDirectory, List.of(snapshot));

			JsonNode result = scope.json().readTree(Files.readString(destination));
			JsonNode sandbox = result.path("sandbox");
			requireThat(strings(result.path("permissions").path("allow")), "allow").contains("Bash");
			requireThat(sandbox.path("autoAllowBashIfSandboxed").asBoolean(true), "autoAllowBash").isTrue();
			requireThat(sandbox.path("enabled").asBoolean(), "sandboxEnabled").isTrue();
			requireThat(sandbox.path("failIfUnavailable").asBoolean(), "failIfUnavailable").isTrue();
			requireThat(sandbox.path("allowUnsandboxedCommands").asBoolean(), "unsandboxedCommands").isFalse();
			requireThat(sandbox.has("excludedCommands"), "excludedCommands").isFalse();
			requireThat(strings(result.path("permissions").path("ask")), "ask").
				isEqualTo(List.of("Bash(git push *)"));
			requireThat(strings(result.path("permissions").path("deny")), "deny").isEqualTo(List.of("Bash(curl *)",
				"Read(//secrets/**)", "Read(/" + ClaudeConnectionSettings.rulePath(snapshot) + ")"));
			requireThat(strings(sandbox.path("filesystem").path("denyRead")), "denyRead").
				isEqualTo(List.of(snapshot.toString()));
			requireThat(strings(sandbox.path("filesystem").path("allowWrite")), "allowWrite").
				isEqualTo(List.of(claudeHome.toString()));
		}
	}

	/**
	 * Verifies isolated settings permit Claude's invocation-local home without inheriting the host home.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void permitsOnlyInvocationLocalHomeWrites() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-home-policy-")).toRealPath();
			Path destination = root.resolve("isolated/.claude/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path claudeHome = claudeTemporaryDirectory.resolve("home");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, claudeHome, List.of(), claudeTemporaryDirectory, List.of());

			JsonNode result = scope.json().readTree(Files.readString(destination));
			JsonNode writablePaths = result.path("sandbox").path("filesystem").path("allowWrite");
			requireThat(writablePaths.size(), "writablePathCount").isEqualTo(1);
			requireThat(writablePaths.path(0).asString(), "writablePath").isEqualTo(claudeHome.toString());
		}
	}

	/**
	 * Verifies that the isolated settings let the session's commands write no path in its project's Git directory and
	 * create none there: Claude Code keeps the directory in which Git registers linked worktrees read-only over any
	 * grant, so the prompt optimizer's registration operations run outside the sandbox instead.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void grantsNoGitWrites() throws IOException
	{
		// Requirement: R-W17
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-worktree-policy-")).toRealPath();
			Path destination = root.resolve("isolated/.claude/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path claudeHome = claudeTemporaryDirectory.resolve("home");
			Path project = Files.createDirectories(root.resolve("session/project/.git")).getParent();
			Path registrations = project.resolve(".git/worktrees");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, claudeHome, List.of(), root.resolve("session"), List.of(), "", null, project);

			String settings = Files.readString(destination);
			List<String> writablePaths = strings(scope.json().readTree(settings).path("sandbox").path("filesystem").
				path("allowWrite"));
			requireThat(writablePaths, "allowWrite").isEqualTo(List.of(claudeHome.toString()));
			requireThat(settings, "settings").doesNotContain(".git/worktrees");
			requireThat(Files.exists(registrations), "registrations").isFalse();
		}
	}

	/**
	 * Verifies that isolated settings deny the session reading each listed file and directory, both through the
	 * sandbox that confines its commands and through the permission rules that govern its file tools, while keeping
	 * the parent's rules that deny reads.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void deniesListedReads() throws IOException
	{
		// Requirement: R-E24
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-denied-reads-")).toRealPath();
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			Path trials = Files.createDirectory(root.resolve("trials"));
			Files.writeString(source, """
				{
				  "sandbox": {"filesystem": {"denyRead": ["/secrets"]}},
				  "permissions": {"deny": ["Read(//secrets/**)"]}
				}
				""");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory, List.of(snapshot, trials));

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(strings(result.path("sandbox").path("filesystem").path("denyRead")), "denyRead").
				isEqualTo(List.of(snapshot.toString(), trials.toString()));
			requireThat(strings(result.path("permissions").path("deny")), "deny").isEqualTo(List.of(
				"Read(//secrets/**)", "Read(/" + ClaudeConnectionSettings.rulePath(snapshot) + ")",
				"Read(/" + ClaudeConnectionSettings.rulePath(trials) + "/**)"));
		}
	}

	/**
	 * Verifies that a permission rule names an absolute path in the form that Claude Code matches rules against on
	 * every platform: a leading {@code /} followed by the path's names, separated by {@code /}.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rulePathsUseSlashes() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-rule-path-"));
			String result = ClaudeConnectionSettings.rulePath(root.resolve("trials").resolve("one.json"));
			requireThat(result, "result").startsWith("/").endsWith("/" + root.getFileName() + "/trials/one.json").
				doesNotContain("\\");
		}
	}

	/**
	 * Verifies that a relative path is rejected, because a rule names an absolute path.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rulePathRejectsRelativePath()
	{
		ClaudeConnectionSettings.rulePath(Path.of("trials"));
	}

	/**
	 * Verifies that isolated settings deny the listed reads when the host has no settings file.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void deniesListedReadsWithoutHostSettings() throws IOException
	{
		// Requirement: R-E24
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-denied-reads-")).toRealPath();
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory,
				List.of(snapshot));

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(strings(result.path("sandbox").path("filesystem").path("denyRead")), "denyRead").
				isEqualTo(List.of(snapshot.toString()));
			requireThat(strings(result.path("permissions").path("deny")), "deny").
				isEqualTo(List.of("Read(/" + ClaudeConnectionSettings.rulePath(snapshot) + ")"));
		}
	}

	/**
	 * Verifies that isolated settings never retain the host's {@code sandbox.filesystem.allowRead}, which would
	 * re-allow reads beyond the session's policy, whether or not reads are denied.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void omitsHostAllowRead() throws IOException
	{
		// Requirement: R-E24
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-allow-read-")).toRealPath();
			Path source = root.resolve("settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			Files.writeString(source, """
				{"sandbox": {"filesystem": {"allowRead": ["/shared"], "denyRead": ["/secrets"]}}}
				""");
			Path denying = root.resolve("denying/settings.json");
			Path allowing = root.resolve("allowing/settings.json");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, denying, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory, List.of(snapshot));
			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, allowing, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory, List.of());

			JsonNode denied = scope.json().readTree(Files.readString(denying)).path("sandbox").path("filesystem");
			requireThat(denied.has("allowRead"), "deniedHasAllowRead").isFalse();
			requireThat(strings(denied.path("denyRead")), "deniedDenyRead").isEqualTo(List.of(snapshot.toString()));
			JsonNode allowed = scope.json().readTree(Files.readString(allowing)).path("sandbox").path("filesystem");
			requireThat(allowed.has("allowRead"), "allowedHasAllowRead").isFalse();
			requireThat(allowed.has("denyRead"), "allowedHasDenyRead").isFalse();
		}
	}

	/**
	 * Verifies that isolated settings exclude every instruction file that Claude Code loads from a directory above
	 * the isolation root, so that a session whose session folder lies inside another project does not receive that
	 * project's instructions, while the files at or below the isolation root still load.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void excludesInstructionsAboveIsolationRoot() throws IOException
	{
		// Requirement: R-C28
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-instruction-excludes-"));
			Path project = root.resolve("project[1]");
			Path sessionFolder = project.resolve(".cat/results/session");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = sessionFolder.resolve("claude");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, sessionFolder.resolve("home"), List.of(), sessionFolder, List.of());

			List<String> excludes = strings(scope.json().readTree(Files.readString(destination)).
				path("claudeMdExcludes"));
			String projectGlob = glob(project);
			String fileSystemGlob = glob(root.getRoot());
			requireThat(excludes, "excludes").containsAll(List.of(projectGlob + "CLAUDE.md",
				projectGlob + "CLAUDE.local.md", projectGlob + ".claude/CLAUDE.md", projectGlob + ".claude/rules/**",
				projectGlob + "AGENTS.md", projectGlob + ".claude/AGENTS.md",
				glob(project.resolve(".cat/results")) + "CLAUDE.md", fileSystemGlob + "CLAUDE.md",
				fileSystemGlob + ".claude/rules/**", fileSystemGlob + "AGENTS.md"));
			for (String exclude : excludes)
			{
				requireThat(exclude, "exclude").doesNotStartWith(glob(sessionFolder));
			}
		}
	}

	/**
	 * Returns a directory as Linux settings start a glob pattern for the files below it.
	 *
	 * @param directory an absolute directory
	 * @return the directory with each backslash and bracket escaped, ending with {@code /}
	 */
	private static String glob(Path directory)
	{
		String text = directory.toString().replaceAll("([\\\\\\[\\]])", "\\\\$1");
		if (text.endsWith("/"))
			return text;
		return text + "/";
	}

	/**
	 * Verifies that isolated settings keep the parent's choice of which instruction files Claude Code loads, and no
	 * other plugin configuration.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsInstructionFilesMode() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-instruction-files-"));
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Files.writeString(source, """
				{
				  "pluginConfigs": {
				    "cc-plugin-agents-md@builtin": {
				      "options": {"instructionFiles": "claude-md-and-agents-md", "unrelated": "value"}
				    },
				    "reviewer@market": {"options": {"token": "secret"}}
				  }
				}
				""");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory, List.of());

			JsonNode pluginConfigs = scope.json().readTree(Files.readString(destination)).path("pluginConfigs");
			requireThat(pluginConfigs, "pluginConfigs").isEqualTo(scope.json().readTree("""
				{"cc-plugin-agents-md@builtin": {"options": {"instructionFiles": "claude-md-and-agents-md"}}}
				"""));
		}
	}

	/**
	 * Verifies that a requested choice of which instruction files load replaces the parent's choice.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void requestedInstructionFilesModeWins() throws IOException
	{
		// Requirement: R-W10
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-instruction-files-"));
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Files.writeString(source, """
				{"pluginConfigs": {"cc-plugin-agents-md@builtin": {"options": {"instructionFiles": "agents-md"}}}}
				""");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory, List.of(), "claude-md");

			JsonNode pluginConfigs = scope.json().readTree(Files.readString(destination)).path("pluginConfigs");
			requireThat(pluginConfigs, "pluginConfigs").isEqualTo(scope.json().readTree("""
				{"cc-plugin-agents-md@builtin": {"options": {"instructionFiles": "claude-md"}}}
				"""));
		}
	}

	/**
	 * Verifies that a requested choice of which instruction files load applies when the parent makes none.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void requestedModeWithoutParentMode() throws IOException
	{
		// Requirement: R-W10
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-instruction-files-"));
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("settings.json"), destination,
				claudeTemporaryDirectory, claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory,
				List.of(), "claude-md");

			JsonNode pluginConfigs = scope.json().readTree(Files.readString(destination)).path("pluginConfigs");
			requireThat(pluginConfigs, "pluginConfigs").isEqualTo(scope.json().readTree("""
				{"cc-plugin-agents-md@builtin": {"options": {"instructionFiles": "claude-md"}}}
				"""));
		}
	}

	/**
	 * Verifies that isolated settings leave the choice of instruction files at Claude Code's default when the parent
	 * makes none.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void omitsUnsetInstructionFilesMode() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-instruction-files-"));
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Files.writeString(source, """
				{"pluginConfigs": {"reviewer@market": {"options": {"token": "secret"}}}}
				""");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), claudeTemporaryDirectory, List.of());

			requireThat(scope.json().readTree(Files.readString(destination)).has("pluginConfigs"), "pluginConfigs").
				isFalse();
		}
	}

	/**
	 * Verifies that the choice of which instruction files load is read from the settings file that makes it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsInstructionFilesMode() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-claude-instruction-files-")).
				resolve("settings.json");
			Files.writeString(source, """
				{"pluginConfigs": {"cc-plugin-agents-md@builtin": {"options": {"instructionFiles": "claude-md"}}}}
				""");

			requireThat(ClaudeConnectionSettings.instructionFilesMode(scope.json(), source), "mode").
				isEqualTo(Optional.of("claude-md"));
		}
	}

	/**
	 * Verifies that a settings file that does not exist makes no choice of which instruction files load.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void absentSettingsMakeNoInstructionFilesMode() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path source = cleanup.add(Files.createTempDirectory("cat-claude-instruction-files-")).
				resolve("settings.json");

			requireThat(ClaudeConnectionSettings.instructionFilesMode(scope.json(), source), "mode").isEmpty();
		}
	}

	/**
	 * Verifies that the isolated settings confine the session's commands to exactly its sandbox policy: its needs
	 * bounded by the policy that the session's runner received. The sandbox is required, no command runs outside it,
	 * and none of the host's {@code sandbox} settings carry over, neither its writable paths, its read rules, its
	 * excluded commands, its weaker nested sandbox nor its network hosts. A dependency cache outside the bound is not
	 * writable, and the network reaches only the session's model host.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rendersChildPolicy() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-child-policy-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path locked = Files.createDirectories(sessionFolder.resolve("home/locked"));
			Path secrets = Files.createDirectories(root.resolve("secrets"));
			Path shared = Files.createDirectories(secrets.resolve("shared"));
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			Path source = Files.writeString(root.resolve("settings.json"), """
				{
				  "sandbox": {
				    "excludedCommands": ["docker *"],
				    "enableWeakerNestedSandbox": true,
				    "network": {"allowedDomains": ["registry.npmjs.org"], "deniedDomains": ["blocked.example"]},
				    "filesystem": {"allowWrite": ["/workspace"], "allowRead": ["/host-shared"], "denyRead": ["/host"]}
				  }
				}
				""");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = sessionFolder.resolve("claude");
			Path claudeHome = sessionFolder.resolve("home");
			ChildPolicy bound = new ChildPolicy(List.of(sessionFolder, cache), List.of(locked), List.of(secrets),
				List.of(shared), Set.of("api.example", "other.example"), false);

			ChildPolicy effective = ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination,
				claudeTemporaryDirectory, claudeHome, List.of(cache), sessionFolder, List.of(snapshot), "", null,
				null, sessionFolder, bound, ExtraPermissions.NONE, "api.example");

			JsonNode sandbox = scope.json().readTree(Files.readString(destination)).path("sandbox");
			requireThat(sandbox.path("enabled").asBoolean(), "enabled").isTrue();
			requireThat(sandbox.path("failIfUnavailable").asBoolean(), "failIfUnavailable").isTrue();
			requireThat(sandbox.path("allowUnsandboxedCommands").asBoolean(true), "unsandboxedCommands").isFalse();
			requireThat(sandbox.has("excludedCommands"), "excludedCommands").isFalse();
			requireThat(sandbox.has("enableWeakerNestedSandbox"), "weakerNestedSandbox").isFalse();
			JsonNode network = sandbox.path("network");
			requireThat(strings(network.path("allowedDomains")), "allowedDomains").isEqualTo(List.of("api.example"));
			requireThat(network.has("deniedDomains"), "deniedDomains").isFalse();
			requireThat(network.path("allowLocalBinding").asBoolean(), "allowLocalBinding").isFalse();
			JsonNode filesystem = sandbox.path("filesystem");
			requireThat(strings(filesystem.path("allowWrite")), "allowWrite").isEqualTo(List.of(claudeHome.toString(),
				cache.toString()));
			requireThat(strings(filesystem.path("denyWrite")), "denyWrite").isEqualTo(List.of(locked.toString()));
			requireThat(strings(filesystem.path("denyRead")), "denyRead").isEqualTo(List.of(secrets.toString(),
				snapshot.toString()));
			requireThat(strings(filesystem.path("allowRead")), "allowRead").isEqualTo(List.of(shared.toString()));
			requireThat(effective.hosts(), "hosts").isEqualTo(Set.of("api.example"));
			requireThat(effective.writeRoots(), "writeRoots").contains(cache);
		}
	}

	/**
	 * Verifies that a session whose parent has no sandbox runs without one: its settings turn Claude Code's sandbox
	 * off, allow Bash by a permission rule, since no one answers an approval prompt, and keep every tool, and its
	 * policy, which bounds the sessions that it requests, says that it has no sandbox. A write or host that its parent's
	 * policy lacks is not refused.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void unsandboxedParentsChildRunsWithoutSandbox() throws IOException
	{
		// Requirement: R-I43
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-unsandboxed-child-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path outside = Files.createDirectories(root.resolve("outside"));
			Path source = Files.writeString(root.resolve("settings.json"), "{}");
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(sessionFolder), List.of(), List.of(), List.of(),
				Set.of("api.example"), false).unsandboxed();

			ChildPolicy effective = ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination,
				sessionFolder.resolve("claude"), sessionFolder.resolve("home"), List.of(outside), sessionFolder, List.of(),
				"", null, null, sessionFolder, bound, ExtraPermissions.NONE, "unlisted.example");

			JsonNode settings = scope.json().readTree(Files.readString(destination));
			requireThat(settings.path("sandbox").path("enabled").isBoolean(), "enabledIsBoolean").isTrue();
			requireThat(settings.path("sandbox").path("enabled").asBoolean(), "enabled").isFalse();
			requireThat(strings(settings.path("permissions").path("allow")), "allow").contains("Bash");
			requireThat(settings.path("permissions").path("deny").toString(), "deny").doesNotContain("mcp__");
			requireThat(effective.sandboxed(), "sandboxed").isFalse();
		}
	}

	/**
	 * Verifies that when the bound denies reading a folder that holds the session's own write root, such as the
	 * project that holds the session folder of a session, the Read rules deny the rest of that folder
	 * but not the write root, so the session's file tools can write there. A read that the session's own needs deny
	 * inside the write root, and the sandbox's read denial of the folder, stay.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readRulesLeaveSessionsWriteRootReadable() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-in-place-")).toRealPath();
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve("notes.txt"), "notes");
			Files.createDirectories(project.resolve("secrets"));
			Path sessionFolder = Files.createDirectories(project.resolve("results/r1"));
			Files.createDirectories(sessionFolder.resolve("home"));
			Path records = Files.createDirectories(sessionFolder.resolve("records"));
			Files.createDirectories(project.resolve("results/r2"));
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(sessionFolder), List.of(), List.of(project), List.of(),
				Set.of("api.example"), false);

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				sessionFolder.resolve("claude"), sessionFolder.resolve("home"), List.of(), sessionFolder, List.of(records), "",
				null, null, sessionFolder, bound, ExtraPermissions.NONE, "api.example");

			JsonNode settings = scope.json().readTree(Files.readString(destination));
			List<String> rules = strings(settings.path("permissions").path("deny"));
			for (String writable : List.of("home/settings.json", "claude/stream.log", "draft/proposal.json"))
			{
				requireThat(denies(rules, sessionFolder.resolve(writable)), "denied").withContext(writable, "path").
					withContext(rules, "rules").isFalse();
			}
			for (Path denied : List.of(project.resolve("notes.txt"), project.resolve("secrets/key.txt"),
				project.resolve("results/r2/data.json"), records.resolve("evidence.json")))
			{
				requireThat(denies(rules, denied), "denied").withContext(denied, "path").withContext(rules, "rules").
					isTrue();
			}
			requireThat(strings(settings.path("sandbox").path("filesystem").path("denyRead")), "denyRead").
				contains(project.toString());
		}
	}

	/**
	 * Indicates whether Read rules deny reading a path.
	 *
	 * @param rules the rules
	 * @param path  the path
	 * @return {@code true} if a rule names the path or a folder that holds it
	 */
	private static boolean denies(List<String> rules, Path path)
	{
		String target = "Read(/" + ClaudeConnectionSettings.rulePath(path) + ")";
		for (String rule : rules)
		{
			if (rule.equals(target))
				return true;
			if (rule.endsWith("/**)") && target.startsWith(rule.substring(0, rule.length() - "/**)".length()) + "/"))
				return true;
		}
		return false;
	}

	/**
	 * Verifies that a session whose dependency cache lies outside every folder its bound lets the commands write is
	 * refused, and the refusal names the cache and the host that the bound does not allow, before any settings file is
	 * written.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesCacheOutsideBound() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-child-refusal-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path outsideCache = Files.createDirectories(root.resolve("outside-cache"));
			Path source = Files.writeString(root.resolve("settings.json"), "{}");
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(sessionFolder), List.of(), List.of(), List.of(),
				Set.of("api.example"), false);

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination,
					sessionFolder.resolve("claude"), sessionFolder.resolve("home"), List.of(outsideCache), sessionFolder,
				List.of(), "", null, null,
					sessionFolder, bound, ExtraPermissions.NONE, "model.example"));

			requireThat(refusal.getMessage(), "message").contains(outsideCache.toString()).contains("model.example");
			requireThat(Files.exists(destination), "written").isFalse();
		}
	}

	/**
	 * Verifies that the writes and hosts that the session's launch asks for beyond its harness's join its needs, so
	 * that the settings let its commands write and reach them when the policy that bounds it allows them.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void rendersExtraPermissions() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-extra-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path registry = Files.createDirectories(root.resolve("registry cache"));
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(sessionFolder, registry), List.of(), List.of(), List.of(),
				Set.of("api.example", "registry.example"), false);
			ExtraPermissions extra = new ExtraPermissions(List.of(registry), Set.of("registry.example"));

			ChildPolicy effective = ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"),
				destination, sessionFolder.resolve("claude"), sessionFolder.resolve("home"), List.of(), sessionFolder,
				List.of(), "", null, null, sessionFolder, bound, extra, "api.example");

			JsonNode sandbox = scope.json().readTree(Files.readString(destination)).path("sandbox");
			requireThat(strings(sandbox.path("filesystem").path("allowWrite")), "allowWrite").contains(registry.toString());
			requireThat(strings(sandbox.path("network").path("allowedDomains")), "allowedDomains").
				isEqualTo(List.of("api.example", "registry.example"));
			requireThat(effective.writeRoots(), "writeRoots").contains(registry);
			requireThat(effective.hosts(), "hosts").isEqualTo(Set.of("api.example", "registry.example"));
		}
	}

	/**
	 * Verifies that a session whose launch asks for a write and a host that the policy bounding it does not allow is
	 * refused, naming both, before any settings file is written.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesExtraPermissionsOutsideBound() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-extra-refusal-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path registry = Files.createDirectories(root.resolve("registry cache"));
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(sessionFolder), List.of(), List.of(), List.of(),
				Set.of("api.example"), false);
			ExtraPermissions extra = new ExtraPermissions(List.of(registry), Set.of("registry.example"));

			MissingPermissionsException refusal = expectThrows(MissingPermissionsException.class,
				() -> ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
					sessionFolder.resolve("claude"), sessionFolder.resolve("home"), List.of(), sessionFolder, List.of(), "", null,
				null, sessionFolder, bound, extra,
					"api.example"));

			requireThat(refusal.getMessage(), "message").contains(registry.toString()).contains("registry.example");
			requireThat(Files.exists(destination), "written").isFalse();
		}
	}

	/**
	 * Verifies that a session whose home, project or temporary directory lies outside its session folder, and which its
	 * policy bound does not let it write, is refused, naming the directory, before any settings are written.
	 *
	 * @param directory the directory that lies outside the session folder: {@code home}, {@code project} or
	 *                  {@code temporary}
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "directoriesOutsideSessionFolder")
	public void refusesSessionOutsideBound(String directory) throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-outside-bound-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
			Path outside = root.resolve("outside").resolve(directory);
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(elsewhere), List.of(), List.of(), List.of(), Set.of(), false);
			Path home = sessionFolder.resolve("home");
			Path project = sessionFolder.resolve("project");
			Path temporary = sessionFolder.resolve("claude");
			switch (directory)
			{
				case "home" -> home = outside;
				case "project" -> project = outside;
				case "temporary" -> temporary = outside;
				default -> throw new AssertionError(directory);
			}
			Path refusedHome = home;
			Path refusedProject = project;
			Path refusedTemporary = temporary;

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
					refusedTemporary, refusedHome, List.of(), sessionFolder, List.of(), "", null, refusedProject, sessionFolder,
				bound,
					ExtraPermissions.NONE, "api.example"));

			requireThat(refusal.getMessage(), "message").contains(outside.toString());
			requireThat(Files.exists(destination), "settingsWritten").isFalse();
		}
	}

	/**
	 * Returns the directories of a session that may lie outside its session folder.
	 *
	 * @return the directory's name
	 */
	@DataProvider
	public Object[][] directoriesOutsideSessionFolder()
	{
		return new Object[][]{{"home"}, {"project"}, {"temporary"}};
	}

	/**
	 * Verifies that a session whose session folder lies inside a folder that its policy bound does not let it write, as
	 * a requested session's does, starts: the bound that holds only its requester's home, requests directory and
	 * dependency cache lets it write its own session folder, and the session's writable paths stay its home and the
	 * cache, not the session folder, which also holds what the runner writes.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void allowsSessionFolderOutsideBound() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-nested-session-")).toRealPath();
			Path requesterSession = Files.createDirectories(root.resolve("session"));
			Path sessionFolder = Files.createDirectories(requesterSession.resolve("requested-results/0/1"));
			Path requesterHome = Files.createDirectories(requesterSession.resolve("home"));
			Path requests = Files.createDirectories(requesterSession.resolve("requests"));
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(requesterHome, requests, cache), List.of(), List.of(),
				List.of(), Set.of("api.example"), false);
			Path home = sessionFolder.resolve("home");
			Path project = Files.createDirectories(sessionFolder.resolve("project/.git"));

			ChildPolicy effective = ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"),
				destination, sessionFolder.resolve("claude"), home, List.of(cache), sessionFolder, List.of(), "", null,
				project.getParent(), sessionFolder, bound, ExtraPermissions.NONE, "api.example");

			JsonNode sandbox = scope.json().readTree(Files.readString(destination)).path("sandbox");
			requireThat(strings(sandbox.path("filesystem").path("allowWrite")), "allowWrite").isEqualTo(
				List.of(home.toString(), cache.toString()));
			requireThat(effective.writeRoots(), "writeRoots").doesNotContain(sessionFolder);
		}
	}

	/**
	 * Verifies that a session that another session requests, which the requester's policy bounds with a denial of the
	 * folder that holds the requester's requested sessions, is denied its sibling sessions and its own records but not
	 * its own responses directory, where its launcher reads the responses to its own requests.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsOwnResponsesReadable() throws IOException
	{
		// Requirement: R-I11
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-own-responses-")).toRealPath();
			Path requester = Files.createDirectories(root.resolve("slots/d1/claude/s-1"));
			Path requestedSessions = Files.createDirectories(requester.resolve("slots"));
			Path sessionFolder = Files.createDirectories(requestedSessions.resolve("d1/claude/s-1"));
			Path sibling = Files.createDirectories(requestedSessions.resolve("d1/claude/s-2/project"));
			Path home = Files.createDirectories(sessionFolder.resolve("home"));
			Path project = Files.createDirectories(sessionFolder.resolve("project/.git"));
			Path requests = Files.createDirectories(sessionFolder.resolve("requests"));
			Path responses = Files.createDirectories(sessionFolder.resolve("responses"));
			Path records = Files.writeString(sessionFolder.resolve("session.json"), "{}");
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(requests, cache), List.of(), List.of(requestedSessions),
				List.of(), Set.of("api.example"), false);
			SessionRequestAccess access = new SessionRequestAccess(requests, responses,
				root.resolve("requested-results"), root.resolve("artifact/bin/request-session"),
				SessionRequestAccess.Shell.POSIX);

			ChildPolicy effective = ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"),
				destination, sessionFolder.resolve("claude"), home, List.of(cache), sessionFolder, List.of(), "", access,
				project.getParent(), sessionFolder, bound, ExtraPermissions.NONE, "api.example");

			JsonNode settings = scope.json().readTree(Files.readString(destination));
			List<String> denied = strings(settings.path("sandbox").path("filesystem").path("denyRead"));
			requireThat(covers(denied, responses), "deniesOwnResponses").withContext(denied, "denyRead").isFalse();
			requireThat(covers(denied, requests), "deniesOwnRequests").withContext(denied, "denyRead").isFalse();
			requireThat(covers(denied, sibling), "deniesSibling").withContext(denied, "denyRead").isTrue();
			requireThat(covers(denied, records), "deniesRecords").withContext(denied, "denyRead").isTrue();
			List<String> inherited = new ArrayList<>();
			for (Path path : effective.readDenials())
				inherited.add(path.toString());
			requireThat(covers(inherited, responses), "inheritedDeniesOwnResponses").isFalse();
			requireThat(covers(inherited, sibling), "inheritedDeniesSibling").isTrue();
		}
	}

	/**
	 * Indicates whether any of some paths is a path or a folder that holds it.
	 *
	 * @param denied the paths
	 * @param path   the path
	 * @return {@code true} if a path of {@code denied} is {@code path} or holds it
	 */
	private static boolean covers(List<String> denied, Path path)
	{
		for (String entry : denied)
		{
			if (path.startsWith(Path.of(entry)))
				return true;
		}
		return false;
	}

	/**
	 * Verifies that the runner refuses a session whose read denials total more paths than Claude Code can sandbox its
	 * commands with, rather than start a session in which every command waits for an approval that no one can give,
	 * and writes no settings for it.
	 * <p>
	 * Keeps 6,000 long denial paths through the settings writer to exercise the sandbox size refusal before any
	 * settings are written. A small path list would not reach the refusal this test verifies.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesDenialsTooManyToSandbox() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-many-denials-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path destination = root.resolve("isolated/settings.json");
			Path denied = root.resolve("x".repeat(180));
			List<Path> deniedReads = new ArrayList<>();
			for (int i = 0; i < 6_000; ++i)
				deniedReads.add(denied.resolve(String.valueOf(i)));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
					sessionFolder.resolve("claude"), sessionFolder.resolve("home"), List.of(), sessionFolder, deniedReads, "",
				null, null, sessionFolder, null,
					ExtraPermissions.NONE, "api.example"));

			requireThat(refusal.getMessage(), "message").contains("read denials");
			requireThat(Files.exists(destination), "written").isFalse();
		}
	}

	/**
	 * Verifies that a session whose session folder lies outside its policy bound's write roots is still refused when a
	 * dependency cache that it needs is not among them, and the refusal names the cache.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesCacheMissingFromNestedBound() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-nested-cache-")).toRealPath();
			Path requesterSession = Files.createDirectories(root.resolve("session"));
			Path sessionFolder = Files.createDirectories(requesterSession.resolve("requested-results/0/1"));
			Path requesterHome = Files.createDirectories(requesterSession.resolve("home"));
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(requesterHome), List.of(), List.of(), List.of(),
				Set.of("api.example"), false);

			MissingPermissionsException refusal = expectThrows(MissingPermissionsException.class,
				() -> ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
					sessionFolder.resolve("claude"), sessionFolder.resolve("home"), List.of(cache), sessionFolder, List.of(), "",
				null, null, sessionFolder,
					bound, ExtraPermissions.NONE, "api.example"));

			requireThat(refusal.getMessage(), "message").contains(cache.toString());
			requireThat(Files.exists(destination), "written").isFalse();
		}
	}

	/**
	 * Returns settings that would widen a session's sandbox beyond its policy, each with the setting that the refusal
	 * must name.
	 *
	 * @return the settings file's content and the setting
	 */
	@DataProvider
	public Object[][] wideningSettings()
	{
		return new Object[][]{
			{"{\"sandbox\": {\"enabled\": false}}", "sandbox.enabled"},
			{"{\"sandbox\": {\"failIfUnavailable\": false}}", "sandbox.failIfUnavailable"},
			{"{\"sandbox\": {\"allowUnsandboxedCommands\": true}}", "sandbox.allowUnsandboxedCommands"},
			{"{\"sandbox\": {\"excludedCommands\": [\"docker *\"]}}", "sandbox.excludedCommands"},
			{"{\"sandbox\": {\"enableWeakerNestedSandbox\": true}}", "sandbox.enableWeakerNestedSandbox"},
			{"{\"sandbox\": {\"filesystem\": {\"allowWrite\": [\"/etc\"]}}}", "sandbox.filesystem.allowWrite"},
			{"{\"sandbox\": {\"filesystem\": {\"allowRead\": [\"SECRETS\"]}}}", "sandbox.filesystem.allowRead"},
			{"{\"sandbox\": {\"network\": {\"allowedDomains\": [\"*.example\"]}}}",
				"sandbox.network.allowedDomains"},
			{"{\"sandbox\": {\"network\": {\"allowLocalBinding\": true}}}", "sandbox.network.allowLocalBinding"},
			{"{\"sandbox\": {\"network\": {\"allowUnixSockets\": [\"/run/docker.sock\"]}}}",
				"sandbox.network.allowUnixSockets"},
			{"{\"sandbox\": {\"network\": {\"allowAllUnixSockets\": true}}}",
				"sandbox.network.allowAllUnixSockets"},
			{"{\"sandbox\": {\"network\": {\"httpProxyPort\": 8080}}}", "sandbox.network.httpProxyPort"},
			{"{\"permissions\": {\"additionalDirectories\": [\"/etc\"]}}", "permissions.additionalDirectories"},
			{"{\"permissions\": {\"allow\": [\"Edit(//etc/**)\"]}}", "permissions.allow"},
			{"{\"permissions\": {\"allow\": [\"Write\"]}}", "permissions.allow"}};
	}

	/**
	 * Verifies that a settings file that the session also loads, such as a managed settings file or the copied
	 * project's settings, is refused when it would widen the session's sandbox beyond its policy, naming the file
	 * and the setting.
	 *
	 * @param content the settings file's content, in which {@code SECRETS} stands for a folder that the policy denies
	 *                reading
	 * @param setting the setting that the refusal must name
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "wideningSettings")
	public void refusesWideningSettings(String content, String setting) throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-widening-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path secrets = Files.createDirectories(root.resolve("secrets"));
			ChildPolicy policy = new ChildPolicy(List.of(sessionFolder), List.of(), List.of(secrets), List.of(),
				Set.of("api.example"), false);
			Path narrow = Files.writeString(root.resolve("narrow.json"), "{\"sandbox\": {\"enabled\": true}}");
			// A Windows path holds backslashes, which a JSON string spells with another backslash each
			Path widening = Files.writeString(root.resolve("widening.json"), content.replace("SECRETS",
				secrets.toString().replace("\\", "\\\\")));

			Optional<String> refusal = ClaudeConnectionSettings.findWidening(scope.json(), List.of(narrow, widening),
				policy, sessionFolder.resolve("home"));

			requireThat(refusal.orElseThrow(), "refusal").contains(widening.toString()).contains(setting);
		}
	}

	/**
	 * Verifies that settings files that keep the session's sandbox within its policy, or that do not exist, are not
	 * refused.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsSettingsWithinPolicy() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-within-policy-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			ChildPolicy policy = new ChildPolicy(List.of(sessionFolder), List.of(), List.of(), List.of(),
				Set.of("api.example"), false);
			Path settings = Files.writeString(root.resolve("settings.json"), """
				{
				  "sandbox": {
				    "enabled": true,
				    "failIfUnavailable": true,
				    "filesystem": {"allowWrite": ["%s"], "denyRead": ["/elsewhere"]},
				    "network": {"allowedDomains": ["API.example"]}
				  },
				  "permissions": {"allow": ["Bash(git status:*)", "Edit(/%s/**)"], "deny": ["Bash(curl *)"]}
				}
				""".formatted(sessionFolder.resolve("work").toString().replace("\\", "\\\\"),
				ClaudeConnectionSettings.rulePath(sessionFolder)));

			Optional<String> refusal = ClaudeConnectionSettings.findWidening(scope.json(), List.of(settings,
				root.resolve("missing.json")), policy, sessionFolder.resolve("home"));

			requireThat(refusal, "refusal").isEmpty();
		}
	}

	/**
	 * Verifies that a project or managed settings file that sets one of the credential variables in its {@code env},
	 * or sets {@code apiKeyHelper}, is refused, naming the file and the setting: Claude Code would load it beside the
	 * session's own settings and replace the source home's credential. One that sets an unrelated {@code env} variable
	 * is accepted.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesCredentialSettings() throws IOException
	{
		// Requirement: R-I14
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-credential-settings-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			ChildPolicy policy = new ChildPolicy(List.of(sessionFolder), List.of(), List.of(), List.of(),
				Set.of("api.example"), false);
			Map<String, String> settings = new LinkedHashMap<>();
			for (String variable : List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL",
				"CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_SECURESTORAGE_CONFIG_DIR"))
			{
				settings.put("env." + variable, """
					{"env": {"%s": "value"}}
					""".formatted(variable));
			}
			settings.put("apiKeyHelper", """
				{"apiKeyHelper": "printf key"}
				""");
			for (Map.Entry<String, String> entry : settings.entrySet())
			{
				Path file = Files.writeString(root.resolve("settings.json"), entry.getValue());

				Optional<String> refusal = ClaudeConnectionSettings.findWidening(scope.json(), List.of(file), policy,
					sessionFolder.resolve("home"));

				requireThat(refusal, entry.getKey()).isPresent();
				requireThat(refusal.get(), entry.getKey()).contains(file.toString()).contains(entry.getKey());
			}

			Path unrelated = Files.writeString(root.resolve("unrelated.json"), """
				{"env": {"CLAUDE_CODE_MAX_OUTPUT_TOKENS": "4096"}}
				""");
			Optional<String> accepted = ClaudeConnectionSettings.findWidening(scope.json(), List.of(unrelated), policy,
				sessionFolder.resolve("home"));
			requireThat(accepted, "unrelated").isEmpty();
		}
	}

	/**
	 * Verifies that the managed settings that Claude Code loads from a folder are its {@code managed-settings.json}
	 * followed by the JSON files of its {@code managed-settings.d} folder in name order.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void listsManagedSettings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-managed-"));
			Path main = Files.writeString(root.resolve("managed-settings.json"), "{}");
			Path drops = Files.createDirectories(root.resolve("managed-settings.d"));
			Path second = Files.writeString(drops.resolve("20-network.json"), "{}");
			Path first = Files.writeString(drops.resolve("10-sandbox.json"), "{}");
			Files.writeString(drops.resolve("notes.txt"), "");

			requireThat(ClaudeConnectionSettings.managedSettings(root), "managedSettings").
				isEqualTo(List.of(main, first, second));
		}
	}

	/**
	 * Verifies that the session's model host is the host of the connection's base URL, and Anthropic's API host
	 * without one.
	 */
	@Test
	public void modelHostFollowsBaseUrl()
	{
		requireThat(ClaudeConnectionSettings.modelHost(Map.of("ANTHROPIC_BASE_URL", "https://Proxy.example:8443/v1")),
			"configured").isEqualTo("proxy.example");
		requireThat(ClaudeConnectionSettings.modelHost(Map.of()), "default").isEqualTo("api.anthropic.com");
	}

	/**
	 * Returns the strings of a JSON array.
	 *
	 * @param array the array, or a missing node
	 * @return the elements' text, empty if {@code array} is not an array
	 */
	private static List<String> strings(JsonNode array)
	{
		List<String> result = new ArrayList<>();
		for (JsonNode element : array)
			result.add(element.asString());
		return result;
	}
}
