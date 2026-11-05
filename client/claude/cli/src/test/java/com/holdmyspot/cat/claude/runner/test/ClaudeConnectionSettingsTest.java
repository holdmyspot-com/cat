package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
				root.resolve("requested-runs"), launcher, SessionRequestAccess.Shell.POSIX);

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", access, null);
			JsonNode allow = scope.json().readTree(Files.readString(destination)).path("permissions").path("allow");
			requireThat(allow.size(), "grantCount").isEqualTo(7);
			requireThat(allow.path(2).asString(), "launcherGrant").isEqualTo("Bash(" + launcher + ":*)");
			requireThat(allow.path(3).asString(), "claudeRunnerGrant").isEqualTo("Bash(" +
				launcher.resolveSibling("claude-runner") + ":*)");
			requireThat(allow.path(4).asString(), "codexRunnerGrant").isEqualTo("Bash(" +
				launcher.resolveSibling("codex-runner") + ":*)");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", null, null);
			allow = scope.json().readTree(Files.readString(destination)).path("permissions").path("allow");
			requireThat(allow.size(), "defaultGrantCount").isEqualTo(3);
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
				root.resolve("requested-runs"), launcher, SessionRequestAccess.Shell.POSIX);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", null, null);
			String defaultSettings = Files.readString(destination);

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				root.resolve("tmp"), root.resolve("home"), List.of(), root, List.of(), "", access, null);
			JsonNode settings = scope.json().readTree(Files.readString(destination));
			List<String> grants = new ArrayList<>();
			for (JsonNode grant : settings.path("permissions").path("allow"))
				grants.add(grant.asString());
			requireThat(grants, "grants").isEqualTo(List.of("Skill", "Edit(./**)", "Bash(" + launcher + ":*)",
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
	 * Preserves the process connection endpoint over settings without copying unrelated host configuration.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void preservesProcessConnectionEndpoint() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path settings = cleanup.add(Files.createTempFile("cat-claude-process-endpoint-", ".json"));
			Files.writeString(settings, """
				{"env":{"ANTHROPIC_BASE_URL":"https://settings.example"}}
				""");
			Map<String, String> environment = new HashMap<>();
			ClaudeConnectionSettings.copyProcessEnvironment(Map.of(
				"ANTHROPIC_BASE_URL", "https://process.example",
				"ANTHROPIC_AUTH_TOKEN", "process-token",
				"CLAUDE_CONFIG_DIR", "/host/configuration"), environment);
			ClaudeConnectionSettings.copy(scope, settings, environment);

			requireThat(environment.get("ANTHROPIC_BASE_URL"), "processEndpoint").
				isEqualTo("https://process.example");
			requireThat(environment.get("ANTHROPIC_AUTH_TOKEN"), "processAuthentication").
				isEqualTo("process-token");
			requireThat(environment.containsKey("CLAUDE_CONFIG_DIR"), "hostConfiguration").isFalse();
		}
	}

	/**
	 * Verifies host settings add only missing allow-listed connection variables.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesOnlyMissingConnectionVariables() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path settings = cleanup.add(Files.createTempFile("cat-claude-settings-", ".json"));
			Files.writeString(settings, """
				{
				  "env": {
				    "ANTHROPIC_AUTH_TOKEN": "settings-token",
				    "ANTHROPIC_BASE_URL": "https://gateway.example",
				    "UNRELATED_SETTING": "must-not-copy"
				  }
				}
				""");
			Map<String, String> environment = new HashMap<>(Map.of("ANTHROPIC_AUTH_TOKEN", "process-token"));

			ClaudeConnectionSettings.copy(scope, settings, environment);

			requireThat(environment.get("ANTHROPIC_AUTH_TOKEN"), "processToken").isEqualTo("process-token");
			requireThat(environment.get("ANTHROPIC_BASE_URL"), "configuredBaseUrl").isEqualTo("https://gateway.example");
			requireThat(environment.containsKey("UNRELATED_SETTING"), "unrelatedSetting").isFalse();
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
			requireThat(strings(result.path("permissions").path("allow")), "allow").
				isEqualTo(List.of("Skill", "Edit(./**)", "Bash"));
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
			requireThat(strings(result.path("permissions").path("allow")), "allow").
				isEqualTo(List.of("Skill", "Edit(./**)", "Bash"));
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
	 * Verifies that the isolated settings let the session's commands write the directory in which Git registers the
	 * linked worktrees of its project, which the settings create, and no other path in the project's Git directory.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void permitsOnlyGitWorktreeWrites() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-worktree-policy-")).toRealPath();
			Path destination = root.resolve("isolated/.claude/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path claudeHome = claudeTemporaryDirectory.resolve("home");
			Path project = Files.createDirectories(root.resolve("run/project/.git")).getParent();
			Path registrations = project.resolve(".git/worktrees");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, claudeHome, List.of(), root.resolve("run"), List.of(), "", null, project);

			List<String> writablePaths = strings(scope.json().readTree(Files.readString(destination)).
				path("sandbox").path("filesystem").path("allowWrite"));
			requireThat(writablePaths, "allowWrite").isEqualTo(List.of(claudeHome.toString(),
				registrations.toString()));
			requireThat(registrations, "registrations").isDirectory();
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
	 * the isolation root, so that a session whose run directory lies inside another project does not receive that
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
			Path run = project.resolve(".cat/runs/run");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = run.resolve("claude");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, run.resolve("home"), List.of(), run, List.of());

			List<String> excludes = strings(scope.json().readTree(Files.readString(destination)).
				path("claudeMdExcludes"));
			String projectGlob = glob(project);
			String fileSystemGlob = glob(root.getRoot());
			requireThat(excludes, "excludes").containsAll(List.of(projectGlob + "CLAUDE.md",
				projectGlob + "CLAUDE.local.md", projectGlob + ".claude/CLAUDE.md", projectGlob + ".claude/rules/**",
				projectGlob + "AGENTS.md", projectGlob + ".claude/AGENTS.md",
				glob(project.resolve(".cat/runs")) + "CLAUDE.md", fileSystemGlob + "CLAUDE.md",
				fileSystemGlob + ".claude/rules/**", fileSystemGlob + "AGENTS.md"));
			for (String exclude : excludes)
			{
				requireThat(exclude, "exclude").doesNotStartWith(glob(run));
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
			Path run = Files.createDirectories(root.resolve("run"));
			Path cache = Files.createDirectories(root.resolve("cache"));
			Path outsideCache = Files.createDirectories(root.resolve("outside-cache"));
			Path locked = Files.createDirectories(run.resolve("home/locked"));
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
			Path claudeTemporaryDirectory = run.resolve("claude");
			Path claudeHome = run.resolve("home");
			ChildPolicy bound = new ChildPolicy(List.of(run, cache), List.of(locked), List.of(secrets),
				List.of(shared), Set.of("api.example", "other.example"), false);

			ChildPolicy effective = ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination,
				claudeTemporaryDirectory, claudeHome, List.of(cache, outsideCache), run, List.of(snapshot), "", null,
				null, bound, "api.example");

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
			requireThat(effective.writeRoots(), "writeRoots").doesNotContain(outsideCache);
		}
	}

	/**
	 * Verifies that a session whose home, project or temporary directory its policy bound does not let it write is
	 * refused, naming the directory, before any settings are written.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesSessionOutsideBound() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-outside-bound-")).toRealPath();
			Path run = Files.createDirectories(root.resolve("run"));
			Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
			Path destination = root.resolve("isolated/settings.json");
			ChildPolicy bound = new ChildPolicy(List.of(elsewhere), List.of(), List.of(), List.of(), Set.of(), false);
			try
			{
				ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
					run.resolve("claude"), run.resolve("home"), List.of(), run, List.of(), "", null, null, bound,
					"api.example");
				throw new AssertionError("The session was not refused");
			}
			catch (SessionRefusedException e)
			{
				requireThat(e.getMessage(), "message").contains(run.resolve("home").toString());
			}
			requireThat(Files.exists(destination), "settingsWritten").isFalse();
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
			Path run = Files.createDirectories(root.resolve("run"));
			Path secrets = Files.createDirectories(root.resolve("secrets"));
			ChildPolicy policy = new ChildPolicy(List.of(run), List.of(), List.of(secrets), List.of(),
				Set.of("api.example"), false);
			Path narrow = Files.writeString(root.resolve("narrow.json"), "{\"sandbox\": {\"enabled\": true}}");
			Path widening = Files.writeString(root.resolve("widening.json"), content.replace("SECRETS",
				secrets.toString()));

			Optional<String> refusal = ClaudeConnectionSettings.findWidening(scope.json(), List.of(narrow, widening),
				policy, run.resolve("home"));

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
			Path run = Files.createDirectories(root.resolve("run"));
			ChildPolicy policy = new ChildPolicy(List.of(run), List.of(), List.of(), List.of(),
				Set.of("api.example"), false);
			Path settings = Files.writeString(root.resolve("settings.json"), """
				{
				  "sandbox": {
				    "enabled": true,
				    "failIfUnavailable": true,
				    "filesystem": {"allowWrite": ["%s"], "denyRead": ["/elsewhere"]},
				    "network": {"allowedDomains": ["API.example"]}
				  },
				  "permissions": {"allow": ["Bash(git status:*)", "Edit(//%s/**)"], "deny": ["Bash(curl *)"]}
				}
				""".formatted(run.resolve("work"), run.toString().substring(1)));

			Optional<String> refusal = ClaudeConnectionSettings.findWidening(scope.json(), List.of(settings,
				root.resolve("missing.json")), policy, run.resolve("home"));

			requireThat(refusal, "refusal").isEmpty();
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
