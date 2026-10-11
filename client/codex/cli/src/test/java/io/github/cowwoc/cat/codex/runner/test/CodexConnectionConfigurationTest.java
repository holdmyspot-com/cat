package io.github.cowwoc.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.runner.CodexConnectionConfiguration;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexConnectionConfiguration}.
 */
public final class CodexConnectionConfigurationTest
{
	/**
	 * Verifies an isolated Codex configuration retains connection settings but excludes host customizations.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesOnlyConnectionSettings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-configuration-"));
			Path source = root.resolve("host.toml");
			Path destination = root.resolve("isolated/config.toml");
			Files.writeString(source, """
				default_permissions = "cat.client"
				openai_base_url = "http://localhost:2455/backend-api/codex"
				forced_login_method = "api"
				model = "gpt-6-sol"
				model_reasoning_effort = "medium"
				approval_policy = "never"

				[plugins."cat@cat"]
				enabled = true
				[marketplaces.cat]
					source = "/host/plugin"
				[mcp_servers.example]
					url = "https://example.com/mcp"
				""");

			CodexConnectionConfiguration.copy(source, destination);

			String result = Files.readString(destination);
			requireThat(result, "connectionSettings").contains("default_permissions = \"cat-runner\"").
				contains("openai_base_url").contains("forced_login_method").contains("model =").
				contains("model_reasoning_effort").doesNotContain("default_permissions = \"cat.client\"").
				doesNotContain("approval_policy").doesNotContain("plugins").doesNotContain("marketplaces").
				doesNotContain("mcp_servers");
		}
	}

	/**
	 * Verifies every fresh runner home disables Codex's startup update check even without a host configuration file.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void createsUpdateCheckPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-update-check-"));
			Path destination = root.resolve("isolated/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination);

			requireThat(Files.readString(destination), "configuration").contains(
				"check_for_update_on_startup = false");
		}
	}

	/**
	 * Verifies the isolated workspace-write sandbox retains the parent's explicit network-access policy.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesWorkspaceWriteNetworkAccess() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-network-access-"));
			Path source = root.resolve("host.toml");
			Path destination = root.resolve("isolated/config.toml");
			Files.writeString(source, """
				[sandbox_workspace_write]
				network_access = true
				""");

			CodexConnectionConfiguration.copy(source, destination);

			requireThat(Files.readString(destination), "configuration").contains("[sandbox_workspace_write]").
				contains("network_access = true");
		}
	}

	/**
	 * Verifies the isolated workspace-write sandbox retains an explicit parent network denial.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesWorkspaceWriteNetworkDenial() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-network-denial-"));
			Path source = root.resolve("host.toml");
			Path destination = root.resolve("isolated/config.toml");
			Files.writeString(source, """
				[sandbox_workspace_write]
				network_access = false
				""");

			CodexConnectionConfiguration.copy(source, destination);

			requireThat(Files.readString(destination), "configuration").contains("network_access = false");
		}
	}

	/**
	 * Verifies a nested Codex shell uses the verified npm cache instead of a default cache that its sandbox may expose
	 * read-only.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void configuresNestedNpmCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-npm-cache-"));
			Path cache = Files.createDirectories(root.resolve("npm-cache"));
			Path destination = root.resolve("isolated/config.toml");
			Path temporaryDirectory = root.resolve("isolated/tmp");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, cache, temporaryDirectory);

			String configuration = Files.readString(destination);
			requireThat(configuration, "configuration").contains("[sandbox_workspace_write]").contains(
				"writable_roots = [\"" + cache + "\"]").contains("[shell_environment_policy.set]").contains(
				"NPM_CONFIG_CACHE = \"" + cache + "\"").contains("TMPDIR = \"" + temporaryDirectory + "\"");
		}
	}

	/**
	 * Verifies a session under a tool-output token limit gets Codex's top-level {@code tool_output_token_limit}
	 * setting, ahead of every table, and that a session without one keeps Codex's default.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void limitsToolOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-tool-output-"));
			Path source = root.resolve("host.toml");
			Files.writeString(source, """
				model = "gpt-6-luna"
				tool_output_token_limit = 20000

				[sandbox_workspace_write]
				network_access = true
				""");
			Path limited = root.resolve("limited/config.toml");
			Path unlimited = root.resolve("unlimited/config.toml");

			CodexConnectionConfiguration.copy(source, limited, null, root.resolve("limited/tmp"), 5000);
			CodexConnectionConfiguration.copy(source, unlimited, null, root.resolve("unlimited/tmp"), 0);

			List<String> lines = Files.readAllLines(limited);
			int setting = lines.indexOf("tool_output_token_limit = 5000");
			requireThat(setting, "setting").isNotNegative();
			for (String line : lines.subList(0, setting))
				requireThat(line, "line").doesNotStartWith("[");
			requireThat(lines.stream().filter(line -> line.startsWith("tool_output_token_limit")).count(),
				"settings").isEqualTo(1L);
			requireThat(Files.readString(unlimited), "unlimited").doesNotContain("tool_output_token_limit");
		}
	}

	/**
	 * Verifies an isolated Codex process retains the parent's CAT filesystem permission policy.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesCatPermissionPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-permissions-"));
			Path source = root.resolve("host.toml");
			Path destination = root.resolve("isolated/config.toml");
			Files.writeString(source, """
				[permissions.cat]
				extends = ":workspace"
				[permissions.cat.filesystem]
				"/cache" = "write"
				[permissions.cat.filesystem.":workspace_roots"]
				"." = "write"
				[permissions.other]
				"/secret" = "write"
				""");

			CodexConnectionConfiguration.copy(source, destination);

			requireThat(Files.readString(destination), "configuration").contains("[permissions.cat]").
				contains("\"/cache\" = \"write\"").contains("[permissions.cat.filesystem.\":workspace_roots\"]").
				doesNotContain("[permissions.other]");
		}
	}

	/**
	 * Verifies a validator profile can inspect immutable executor evidence without making it writable.
	 */
	@Test
	public void addsReadOnlyEvidenceProfile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-validator-permissions-"));
			Path destination = root.resolve("isolated/config.toml");

			Files.createDirectories(destination.getParent());
			Files.writeString(destination, "[permissions.cat-runner]\nextends = \":workspace\"\n");
			Path evidence = Files.createDirectories(root.resolve("evidence"));
			Path canonicalEvidence = evidence.toRealPath();
			CodexConnectionConfiguration.addValidatorProfile(destination, List.of(evidence));

			requireThat(Files.readString(destination), "configuration").contains("[permissions.cat-validator]").
				contains("extends = \"cat-runner\"").contains("\"" + canonicalEvidence + "\" = \"read\"").
				doesNotContain("\"" + canonicalEvidence + "\" = \"write\"");
		}
	}

	/**
	 * Verifies that a session with paths to deny runs under a profile that denies reading each of them and extends
	 * the runner profile.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void deniesListedReads() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-denied-reads-"));
			Path destination = root.resolve("isolated/config.toml");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			Path trials = Files.createDirectory(root.resolve("trials"));
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination);

			String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(),
				List.of(snapshot, trials));

			requireThat(profile, "profile").isEqualTo("cat-held-out");
			requireThat(Files.readString(destination), "configuration").contains(String.join(System.lineSeparator(),
				"[permissions.cat-held-out]",
				"extends = \"cat-runner\"",
				"[permissions.cat-held-out.filesystem]",
				"\"" + snapshot.toRealPath() + "\" = \"deny\"",
				"\"" + trials.toRealPath() + "\" = \"deny\"",
				""));
		}
	}

	/**
	 * Verifies that a session that reads evidence and has paths to deny runs under a profile that denies them and
	 * extends the validator profile, which grants the evidence.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void deniesListedReadsToValidators() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-denied-reads-"));
			Path destination = root.resolve("isolated/config.toml");
			Path evidence = Files.createDirectories(root.resolve("evidence"));
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination);

			String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(evidence),
				List.of(snapshot));

			requireThat(profile, "profile").isEqualTo("cat-held-out");
			requireThat(Files.readString(destination), "configuration").
				contains("\"" + evidence.toRealPath() + "\" = \"read\"").
				contains(String.join(System.lineSeparator(),
					"[permissions.cat-held-out]",
					"extends = \"cat-validator\"",
					"[permissions.cat-held-out.filesystem]",
					"\"" + snapshot.toRealPath() + "\" = \"deny\"",
					""));
		}
	}

	/**
	 * Verifies that a session without evidence or paths to deny keeps the workspace-write sandbox.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void keepsSandboxWithoutDeniedReads() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-denied-reads-"));
			Path destination = root.resolve("isolated/config.toml");
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination);
			String before = Files.readString(destination);

			String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(), List.of());

			requireThat(profile, "profile").isEmpty();
			requireThat(Files.readString(destination), "configuration").isEqualTo(before);
		}
	}

	/**
	 * Verifies that the profile that denies reads keeps the parent's workspace-write network access, which a thread
	 * that names a profile instead of the workspace-write sandbox would otherwise lose.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void deniedReadsKeepNetworkAccess() throws IOException
	{
		// Requirement: R-E24
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-denied-reads-"));
			Path source = Files.writeString(root.resolve("host.toml"), """
				[sandbox_workspace_write]
				network_access = true
				""");
			Path destination = root.resolve("isolated/config.toml");
			Path snapshot = Files.writeString(root.resolve("campaign.json"), "{}");
			CodexConnectionConfiguration.copy(source, destination);

			CodexConnectionConfiguration.addSessionProfile(destination, List.of(), List.of(snapshot));

			requireThat(Files.readString(destination), "configuration").
				contains("[permissions.cat-held-out.network]" + System.lineSeparator() + "enabled = true" +
					System.lineSeparator());
		}
	}

	/**
	 * Verifies only CAT's approved hook hashes are copied into an isolated configuration.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesTrustedCatHooks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-hook-trust-"));
			Path source = root.resolve("host.toml");
			Path destination = root.resolve("isolated/config.toml");
			Files.writeString(source, """
				[hooks.state."cat@cat:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:c29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"
				enabled = true

				[hooks.state."cat@cat:hooks/hooks.json:subagent_start:0:0"]
				trusted_hash = "sha256:b29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"

				[hooks.state."cat@cat:hooks/hooks.json:post_tool_use:0:0"]
				trusted_hash = "sha256:d29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"

				[hooks.state."other:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
				""");

			CodexConnectionConfiguration.copyTrustedCatHooks(source, destination);

			String configuration = Files.readString(destination);
			requireThat(configuration, "configuration").contains(
				"[hooks.state.\"cat@cat:hooks/hooks.json:session_start:0:0\"]").contains(
					"trusted_hash = \"sha256:c29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3\"").
				contains("[hooks.state.\"cat@cat:hooks/hooks.json:subagent_start:0:0\"]").contains(
					"trusted_hash = \"sha256:b29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3\"").
				contains("[hooks.state.\"cat@cat:hooks/hooks.json:post_tool_use:0:0\"]").contains(
					"trusted_hash = \"sha256:d29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3\"").
				doesNotContain("other:hooks/hooks.json").doesNotContain("enabled = true");
		}
	}
}
