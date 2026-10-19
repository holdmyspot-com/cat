package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests {@link ClaudeConnectionSettings}.
 */
public final class ClaudeConnectionSettingsTest
{
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
	 * Verifies isolated settings enforce runner-owned safety flags, retain the parent's sandbox policy, and set CAT-owned
	 * Claude environment values.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void writesIsolatedSettings() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-network-policy-"));
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
				      "allowWrite": ["/workspace"],
				      "denyRead": ["/secrets"]
				    }
				  },
				  "permissions": {
				    "additionalDirectories": ["/workspace"],
				    "allow": ["Bash(*)", "Write(//workspace/**)"],
				    "deny": ["Read(//secrets/**)"]
				  }
				}
				""");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), Platform.LINUX_X64);

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(result.path("sandbox").path("network").path("allowedDomains").path(0).asString(),
				"allowedDomain").isEqualTo("registry.npmjs.org");
			requireThat(result.path("sandbox").path("network").path("deniedDomains").path(0).asString(),
				"deniedDomain").isEqualTo("blocked.example");
			requireThat(result.path("sandbox").path("filesystem").path("allowWrite").path(0).asString(),
				"workspaceWrite").isEqualTo("/workspace");
			requireThat(result.path("sandbox").path("filesystem").path("denyRead").path(0).asString(),
				"secretRead").isEqualTo("/secrets");
			requireThat(result.path("permissions").path("allow").path(0).asString(), "allowedCommand").
				isEqualTo("Bash(*)");
			requireThat(result.path("permissions").path("allow").path(1).asString(), "allowedWrite").
				isEqualTo("Write(//workspace/**)");
			requireThat(result.path("permissions").path("deny").path(0).asString(), "deniedRead").
				isEqualTo("Read(//secrets/**)");
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
	 * Verifies isolated settings omit an undeclared parent sandbox policy while retaining runner-owned safeguards and
	 * required environment values.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void omitsUndeclaredSandboxNetworkPolicy() throws IOException
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
				claudeTemporaryDirectory.resolve("home"), List.of(), Platform.LINUX_X64);

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(result.path("env").path("DISABLE_AUTOUPDATER").asString(), "autoUpdater").isEqualTo("1");
			requireThat(result.path("env").path("CLAUDE_CODE_TMPDIR").asString(), "temporaryDirectory").
				isEqualTo(claudeTemporaryDirectory.toString());
			requireThat(result.path("sandbox").path("enabled").asBoolean(), "sandboxEnabled").isTrue();
			requireThat(result.path("sandbox").path("failIfUnavailable").asBoolean(), "failIfUnavailable").isTrue();
			requireThat(result.path("sandbox").path("allowUnsandboxedCommands").asBoolean(), "unsandboxedCommands").
			isFalse();
			requireThat(result.path("sandbox").has("network"), "networkPolicy").isFalse();
		}
	}

	/**
	 * Verifies that on Windows, where Claude Code has no sandbox, isolated settings keep the parent's sandbox
	 * settings instead of requiring a sandbox, while still permitting writes to the invocation-local home and denying
	 * the listed reads.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesParentSandboxOnWindows() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-windows-sandbox-"));
			Path source = root.resolve("settings.json");
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path claudeHome = claudeTemporaryDirectory.resolve("home");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			Files.writeString(source, """
				{
				  "sandbox": {
				    "enabled": false,
				    "autoAllowBashIfSandboxed": true,
				    "filesystem": {"denyRead": ["/secrets"]}
				  }
				}
				""");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, destination, claudeTemporaryDirectory,
				claudeHome, List.of(snapshot), Platform.WINDOWS_X64);

			JsonNode sandbox = scope.json().readTree(Files.readString(destination)).path("sandbox");
			requireThat(sandbox.path("enabled").asBoolean(), "sandboxEnabled").isFalse();
			requireThat(sandbox.has("failIfUnavailable"), "failIfUnavailable").isFalse();
			requireThat(sandbox.has("allowUnsandboxedCommands"), "unsandboxedCommands").isFalse();
			requireThat(sandbox.path("autoAllowBashIfSandboxed").asBoolean(), "autoAllowBash").isTrue();
			requireThat(strings(sandbox.path("filesystem").path("allowWrite")), "allowWrite").
				isEqualTo(List.of(claudeHome.toString()));
			requireThat(strings(sandbox.path("filesystem").path("denyRead")), "denyRead").
				isEqualTo(List.of("/secrets", snapshot.toString()));
		}
	}

	/**
	 * Verifies that on Windows, isolated settings do not enable a sandbox that the parent's settings do not declare.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void addsNoSandboxRequirementOnWindows() throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-windows-no-sandbox-"));
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, claudeTemporaryDirectory.resolve("home"), List.of(), Platform.WINDOWS_X64);

			JsonNode sandbox = scope.json().readTree(Files.readString(destination)).path("sandbox");
			requireThat(sandbox.has("enabled"), "sandboxEnabled").isFalse();
			requireThat(sandbox.has("failIfUnavailable"), "failIfUnavailable").isFalse();
			requireThat(sandbox.has("allowUnsandboxedCommands"), "unsandboxedCommands").isFalse();
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
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-home-policy-"));
			Path destination = root.resolve("isolated/.claude/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path claudeHome = claudeTemporaryDirectory.resolve("home");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, claudeHome, List.of(), Platform.LINUX_X64);

			JsonNode result = scope.json().readTree(Files.readString(destination));
			JsonNode writablePaths = result.path("sandbox").path("filesystem").path("allowWrite");
			requireThat(writablePaths.size(), "writablePathCount").isEqualTo(1);
			requireThat(writablePaths.path(0).asString(), "writablePath").isEqualTo(claudeHome.toString());
		}
	}

	/**
	 * Verifies that isolated settings deny the session reading each listed file and directory, both through the
	 * sandbox that confines its commands and through the permission rules that govern its file tools, while keeping
	 * the parent's own read denials.
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
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-denied-reads-"));
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
				claudeTemporaryDirectory.resolve("home"), List.of(snapshot, trials), Platform.LINUX_X64);

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(strings(result.path("sandbox").path("filesystem").path("denyRead")), "denyRead").
				isEqualTo(List.of("/secrets", snapshot.toString(), trials.toString()));
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
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-denied-reads-"));
			Path destination = root.resolve("isolated/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, claudeTemporaryDirectory.resolve("home"), List.of(snapshot), Platform.LINUX_X64);

			JsonNode result = scope.json().readTree(Files.readString(destination));
			requireThat(strings(result.path("sandbox").path("filesystem").path("denyRead")), "denyRead").
				isEqualTo(List.of(snapshot.toString()));
			requireThat(strings(result.path("permissions").path("deny")), "deny").
				isEqualTo(List.of("Read(/" + ClaudeConnectionSettings.rulePath(snapshot) + ")"));
		}
	}

	/**
	 * Verifies that isolated settings drop the host's {@code sandbox.filesystem.allowRead} when reads are denied,
	 * because it would re-allow the denied reads, and keep it when no read is denied.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void dropsHostAllowReadOnlyWhenReadsAreDenied() throws IOException
	{
		// Requirement: R-E24
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-allow-read-"));
			Path source = root.resolve("settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			Files.writeString(source, """
				{"sandbox": {"filesystem": {"allowRead": ["/shared"], "denyRead": ["/secrets"]}}}
				""");
			Path denying = root.resolve("denying/settings.json");
			Path allowing = root.resolve("allowing/settings.json");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, denying, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(snapshot), Platform.LINUX_X64);
			ClaudeConnectionSettings.writeIsolatedSettings(scope, source, allowing, claudeTemporaryDirectory,
				claudeTemporaryDirectory.resolve("home"), List.of(), Platform.LINUX_X64);

			JsonNode denied = scope.json().readTree(Files.readString(denying)).path("sandbox").path("filesystem");
			requireThat(denied.has("allowRead"), "deniedHasAllowRead").isFalse();
			requireThat(strings(denied.path("denyRead")), "deniedDenyRead").
				isEqualTo(List.of("/secrets", snapshot.toString()));
			JsonNode kept = scope.json().readTree(Files.readString(allowing)).path("sandbox").path("filesystem");
			requireThat(strings(kept.path("allowRead")), "keptAllowRead").isEqualTo(List.of("/shared"));
		}
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
