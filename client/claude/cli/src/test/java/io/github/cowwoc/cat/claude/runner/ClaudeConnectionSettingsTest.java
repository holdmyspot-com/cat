package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
		try (HarnessRunnerScope scope = new HarnessRunnerScope();
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
		try (HarnessRunnerScope scope = new HarnessRunnerScope();
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
		try (HarnessRunnerScope scope = new HarnessRunnerScope();
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
				claudeTemporaryDirectory.resolve("home"));

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
		try (HarnessRunnerScope scope = new HarnessRunnerScope();
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
				claudeTemporaryDirectory.resolve("home"));

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
	 * Verifies isolated settings permit Claude's invocation-local home without inheriting the host home.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void permitsOnlyInvocationLocalHomeWrites() throws IOException
	{
		try (HarnessRunnerScope scope = new HarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-home-policy-"));
			Path destination = root.resolve("isolated/.claude/settings.json");
			Path claudeTemporaryDirectory = root.resolve("cat-temp/claude");
			Path claudeHome = claudeTemporaryDirectory.resolve("home");

			ClaudeConnectionSettings.writeIsolatedSettings(scope, root.resolve("missing.json"), destination,
				claudeTemporaryDirectory, claudeHome);

			JsonNode result = scope.json().readTree(Files.readString(destination));
			JsonNode writablePaths = result.path("sandbox").path("filesystem").path("allowWrite");
			requireThat(writablePaths.size(), "writablePathCount").isEqualTo(1);
			requireThat(writablePaths.path(0).asString(), "writablePath").isEqualTo(claudeHome.toString());
		}
	}
}
