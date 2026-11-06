package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexParentMultiAgentState;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Tests {@link CodexConnectionConfiguration}.
 */
public final class CodexConnectionConfigurationTest
{
	/**
	 * Verifies that the runner configuration grants writes only to its copied project's Git metadata, while keeping
	 * configuration and hooks read-only even through validator and denied-read profiles.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void configuresProjectGitPermissions() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-git-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path git = Files.createDirectories(project.resolve(".git"));
			Path evidence = Files.createDirectories(project.resolve("evidence"));
			Path secret = Files.writeString(project.resolve("secret"), "private");
			Path destination = root.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, root.resolve("project"),
				HarnessDependencyCaches.NONE,
				root.resolve("tmp"), 0, Platform.LINUX_X64);
			String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(evidence),
				List.of(secret));

			JsonNode permissions = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions");
			JsonNode runner = permissions.path("cat-runner").path("filesystem");
			requireThat(runner.path(git.toString()).asString(), "gitAccess").isEqualTo("write");
			requireThat(runner.path(git.resolve("config").toString()).asString(), "configAccess").isEqualTo("read");
			requireThat(runner.path(git.resolve("hooks").toString()).asString(), "hooksAccess").isEqualTo("read");
			requireThat(runner.has(root.resolve(".git").toString()), "sourceRepositoryGranted").isFalse();
			requireThat(profile, "sessionProfile").isEqualTo("cat-denied-reads");
			requireThat(permissions.path(profile).path("extends").asString(), "deniedReadsParent").
				isEqualTo("cat-validator");
			requireThat(permissions.path(profile).path("filesystem").path(secret.toRealPath().toString()).asString(),
				"secretAccess").isEqualTo("deny");
			requireThat(permissions.path("cat-validator").path("extends").asString(), "validatorParent").
				isEqualTo("cat-runner");
			requireThat(permissions.path("cat-validator").path("filesystem").
				path(evidence.toRealPath().toString()).asString(), "evidenceAccess").isEqualTo("read");
		}
	}

	/**
	 * Returns projects whose Git metadata must not receive additional write authority.
	 *
	 * @return the Git entry kind and the session platform
	 */
	@DataProvider
	public Object[][] restrictedProjectGit()
	{
		return new Object[][]
		{
			{"absent", Platform.LINUX_X64},
			{"file", Platform.LINUX_X64},
			{"directory", Platform.WINDOWS_X64}
		};
	}

	/**
	 * Verifies that projects without their own Git directory and Windows sessions receive no Git grant.
	 *
	 * @param entryKind the kind of Git entry in the copied project
	 * @param platform  the platform that the session runs on
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "restrictedProjectGit")
	public void omitsProjectGitGrant(String entryKind, Platform platform) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-git-"));
			Path project = Files.createDirectories(root.resolve("project"));
			Path git = project.resolve(".git");
			if (entryKind.equals("file"))
				Files.writeString(git, "gitdir: elsewhere");
			if (entryKind.equals("directory"))
				Files.createDirectory(git);
			Path destination = root.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, project,
				HarnessDependencyCaches.NONE, root.resolve("tmp"), 0, platform);

			JsonNode runner = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-runner").path("filesystem");
			requireThat(runner.has(git.toString()), "gitGranted").isFalse();
			requireThat(runner.has(git.resolve("config").toString()), "configGranted").isFalse();
			requireThat(runner.has(git.resolve("hooks").toString()), "hooksGranted").isFalse();
		}
	}

	/**
	 * Returns parent configurations and the instruction values that an isolated session must receive. Null expected
	 * values mean the corresponding setting is absent.
	 *
	 * @return the parent configurations, fallback filename lists and byte limits
	 */
	@DataProvider
	public Object[][] projectInstructionSettings()
	{
		return new Object[][]
		{
			{"", null, null},
			{"project_doc_fallback_filenames = []\nproject_doc_max_bytes = 0\n", List.of(), 0L},
			{"""
				project_doc_fallback_filenames = [
				  'GUIDE.md',
				  'team "notes".md', # retain these filenames
				]
				project_doc_max_bytes = 32_768
				""", List.of("GUIDE.md", "team \"notes\".md"), 32_768L},
			{"""
				"project_doc_fallback_filenames" = ['RULES.md']
				"project_doc_max_bytes" = 4096
				[other]
				project_doc_max_bytes = 999
				""", List.of("RULES.md"), 4096L},
			{"""
				[other]
				project_doc_fallback_filenames = ['wrong.md']
				project_doc_max_bytes = 100
				""", null, null}
		};
	}

	/**
	 * Verifies that isolated sessions inherit the parent's top-level instruction filename fallbacks and byte limits,
	 * retaining explicit empty and zero values while leaving unspecified settings absent.
	 *
	 * @param configuration the parent's configuration
	 * @param filenames the expected fallback filenames, or null when unspecified
	 * @param maximumBytes the expected byte limit, or null when unspecified
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "projectInstructionSettings")
	public void inheritsProjectInstructionSettings(String configuration, List<String> filenames, Long maximumBytes)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-instructions-"));
			Path source = Files.writeString(root.resolve("host.toml"), configuration);
			Path destination = root.resolve("isolated/config.toml");

			CodexConnectionConfiguration.copy(source, destination, root.resolve("project"));

			JsonNode result = TomlMapper.builder().build().readTree(Files.readString(destination));
			requireThat(result.has("project_doc_fallback_filenames"), "fallbacksPresent").isEqualTo(filenames != null);
			if (filenames != null)
			{
				List<String> actual = result.get("project_doc_fallback_filenames").values().stream().
					map(JsonNode::asString).toList();
				requireThat(actual, "fallbackFilenames").isEqualTo(filenames);
			}
			requireThat(result.has("project_doc_max_bytes"), "limitPresent").isEqualTo(maximumBytes != null);
			if (maximumBytes != null)
				requireThat(result.get("project_doc_max_bytes").asLong(), "maximumBytes").isEqualTo(maximumBytes);
		}
	}

	/**
	 * Verifies that a malformed parent configuration produces a source-specific diagnostic before an isolated
	 * configuration is written.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesMalformedInstructionConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-instructions-"));
			Path source = Files.writeString(root.resolve("host.toml"), "project_doc_fallback_filenames = [\n");
			Path destination = root.resolve("isolated/config.toml");

			IOException failure = expectThrows(IOException.class, () ->
				CodexConnectionConfiguration.copy(source, destination, root.resolve("project")));

			requireThat(failure.getMessage(), "diagnostic").contains(source.toString());
			requireThat(failure.getCause(), "cause").isNotNull();
			requireThat(Files.exists(destination), "configurationWritten").isFalse();
		}
	}

	/**
	 * Verifies that loader identity includes explicitly configured instruction values, ignores unrelated host settings
	 * and TOML layout, and observes a later change to the configured values.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void identifiesConfiguredInstructionValues() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-instruction-identity-"));
			Path source = root.resolve("host.toml");
			requireThat(CodexConnectionConfiguration.instructionSettings(source).isEmpty(), "missingSettings").isTrue();
			Files.writeString(source, """
				project_doc_max_bytes = 16_384
				project_doc_fallback_filenames = ['GUIDE.md']
				model = 'first'
				""");
			Optional<String> original = CodexConnectionConfiguration.instructionSettings(source);
			requireThat(original.isPresent(), "configuredSettings").isTrue();

			Files.writeString(source, """
				model = 'second'
				"project_doc_fallback_filenames" = [
				  "GUIDE.md",
				]
				"project_doc_max_bytes" = 16384
				""");
			requireThat(CodexConnectionConfiguration.instructionSettings(source), "equivalentSettings").
				isEqualTo(original);

			Files.writeString(source, "project_doc_max_bytes = 16385\nproject_doc_fallback_filenames = ['GUIDE.md']\n");
			requireThat(CodexConnectionConfiguration.instructionSettings(source), "changedSettings").
				isNotEqualTo(original);

			Files.writeString(source, "model = 'third'\n");
			requireThat(CodexConnectionConfiguration.instructionSettings(source).isEmpty(), "unrelatedSettings").isTrue();
		}
	}

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

			CodexConnectionConfiguration.copy(source, destination, root.resolve("project"));

			String result = Files.readString(destination);
			requireThat(result, "connectionSettings").contains("default_permissions = \"cat-runner\"").
				contains("openai_base_url").contains("forced_login_method").contains("model =").
				contains("model_reasoning_effort").doesNotContain("default_permissions = \"cat.client\"").
				doesNotContain("approval_policy").doesNotContain("[plugins.").doesNotContain("marketplaces").
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

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, root.resolve("project"));

			requireThat(Files.readString(destination), "configuration").contains(
				"check_for_update_on_startup = false");
		}
	}

	/**
	 * Verifies that a session takes its working directory, the copied project, as its project root, so that Codex
	 * looks for no root marker in the directories above the project when it creates a thread, even when the host
	 * configuration names markers of its own.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void projectIsItsOwnRoot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-project-root-"));
			Path source = Files.writeString(root.resolve("host.toml"), """
				project_root_markers = [".git", ".hg"]
				model = "gpt-6-sol"
				""");
			Path destination = root.resolve("isolated/config.toml");

			CodexConnectionConfiguration.copy(source, destination, root.resolve("project"),
				HarnessDependencyCaches.NONE, root.resolve("tmp"), 0, Platform.LINUX_X64);

			JsonNode markers = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("project_root_markers");
			requireThat(markers.isArray(), "markersListed").isTrue();
			requireThat(markers.size(), "markers").isEqualTo(0);
		}
	}

	/**
	 * Verifies that a Windows session prefers Codex's MXC sandbox, which enforces a profile that denies reads without an
	 * administrator's setup.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void windowsPrefersMxcSandbox() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-mxc-"));
			Path destination = root.resolve("isolated/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, root.resolve("project"),
				HarnessDependencyCaches.NONE,
				root.resolve("tmp"), 0, Platform.WINDOWS_X64);

			List<String> lines = Files.readAllLines(destination);
			int features = lines.indexOf("[features]");
			requireThat(features, "features").isNotNegative();
			requireThat(lines.get(features + 1), "featureLine").isEqualTo("prefer_mxc = true");
		}
	}

	/**
	 * Verifies that a session on another platform, whose sandbox ignores the MXC preference, gets a configuration
	 * without it, so that adding the preference leaves that platform's sessions unchanged.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void otherPlatformsOmitMxcPreference() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-mxc-"));
			Path destination = root.resolve("isolated/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, root.resolve("project"),
				HarnessDependencyCaches.NONE,
				root.resolve("tmp"), 0, Platform.LINUX_X64);

			requireThat(Files.readString(destination), "configuration").doesNotContain("prefer_mxc");
		}
	}

	/**
	 * Verifies that a session whose policy names hosts may reach exactly those hosts through Codex's network proxy,
	 * and that the host's own workspace-write network setting does not widen it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void limitsNetworkToPolicyHosts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-network-hosts-"));
			Path source = Files.writeString(root.resolve("host.toml"), """
				[sandbox_workspace_write]
				network_access = true
				""");
			Path destination = root.resolve("isolated/config.toml");
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(root, List.of(),
				List.of(), Set.of("chatgpt.com")));

			copy(source, destination, root.resolve("project"), policy);

			JsonNode configuration = TomlMapper.builder().build().readTree(Files.readString(destination));
			requireThat(configuration.path("features").path("network_proxy").asBoolean(), "networkProxy").isTrue();
			JsonNode network = configuration.path("permissions").path("cat-runner").path("network");
			requireThat(network.path("enabled").asBoolean(), "enabled").isTrue();
			requireThat(network.path("domains").propertyNames(), "domains").containsExactly(Set.of("chatgpt.com"));
			requireThat(network.path("domains").path("chatgpt.com").asString(), "access").isEqualTo("allow");
			requireThat(network.has("allow_local_binding"), "allowLocalBinding").isFalse();
			requireThat(configuration.path("sandbox_workspace_write").has("network_access"), "networkAccess").isFalse();
		}
	}

	/**
	 * Returns whether a parent allows local binding and whether its child's loopback model host may bind.
	 *
	 * @return the parent's local binding and the child's
	 */
	@DataProvider
	public Object[][] localBinding()
	{
		return new Object[][]
		{
			{true, true},
			{false, false}
		};
	}

	/**
	 * Verifies that a session whose model host is a loopback address may bind local addresses, which Codex's network
	 * proxy needs to let it reach that host, only when its parent may.
	 *
	 * @param parentBinds {@code true} if the parent may bind local addresses
	 * @param childBinds  {@code true} if the session must be allowed to bind local addresses
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "localBinding")
	public void bindsLocallyOnlyWhenParentMay(boolean parentBinds, boolean childBinds) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-local-binding-"));
			Path destination = root.resolve("isolated/config.toml");
			ParentPolicy parent = new ParentPolicy(true, List.of(root), List.of(), List.of(), List.of(),
				HostAccess.ALL, parentBinds, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(root, List.of(), List.of(),
				Set.of("localhost")));

			copy(root.resolve("missing.toml"), destination, root.resolve("project"), policy);

			JsonNode network = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-runner").path("network");
			requireThat(network.path("domains").path("localhost").asString(), "access").isEqualTo("allow");
			requireThat(network.path("allow_local_binding").asBoolean(false), "allowLocalBinding").
				isEqualTo(childBinds);
		}
	}

	/**
	 * Verifies that the runner refuses a macOS session that must bind local addresses to reach its loopback model host,
	 * since on macOS that permission lets the session's commands reach every loopback port, not only that host.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesLocalBindingOnMacos() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-macos-binding-"));
			Path destination = root.resolve("isolated/config.toml");
			ParentPolicy parent = new ParentPolicy(true, List.of(root), List.of(), List.of(), List.of(),
				HostAccess.ALL, true, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(root, List.of(), List.of(),
				Set.of("localhost")));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination,
					root.resolve("project"), HarnessDependencyCaches.NONE, root.resolve("tmp"), 0, null,
					Platform.MACOS_ARM64, CodexParentMultiAgentState.NONE, policy));

			requireThat(refusal.getMessage(), "message").contains("localhost").contains("macOS");
			requireThat(Files.exists(destination), "configured").isFalse();
		}
	}

	/**
	 * Verifies that a session whose policy names no host has network off and does not enable Codex's network proxy,
	 * even when the host's workspace-write sandbox has network on.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void disablesNetworkWithoutHosts() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-network-off-"));
			Path source = Files.writeString(root.resolve("host.toml"), """
				[sandbox_workspace_write]
				network_access = true
				""");
			Path destination = root.resolve("isolated/config.toml");
			ParentPolicy parent = new ParentPolicy(true, List.of(root), List.of(), List.of(), List.of(),
				HostAccess.NONE, false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(root, List.of(), List.of(), Set.of()));

			copy(source, destination, root.resolve("project"), policy);

			String text = Files.readString(destination);
			JsonNode configuration = TomlMapper.builder().build().readTree(text);
			requireThat(configuration.path("features").has("network_proxy"), "networkProxy").isFalse();
			JsonNode network = configuration.path("permissions").path("cat-runner").path("network");
			requireThat(network.path("enabled").isBoolean(), "enabledSet").isTrue();
			requireThat(network.path("enabled").asBoolean(), "enabled").isFalse();
			requireThat(network.has("domains"), "domains").isFalse();
			requireThat(text, "configuration").doesNotContain("network_access");
		}
	}

	/**
	 * Returns Codex settings of the user's and the login that each names as the session's model host.
	 *
	 * @return the configuration, the environment, the login file's content or {@code null} when there is none, and
	 *   the model host
	 */
	@DataProvider
	public Object[][] modelHosts()
	{
		return new Object[][]
		{
			{"openai_base_url = \"http://LocalHost:2455/backend-api/codex\"\n", Map.of(), null, "localhost"},
			{"openai_base_url = \"https://proxy.example.com/v1\"\n", Map.of("OPENAI_API_KEY", "sk-test"), null,
				"proxy.example.com"},
			{"", Map.of("OPENAI_API_KEY", "sk-test"), null, "api.openai.com"},
			{"", Map.of("CODEX_API_KEY", " "), null, "chatgpt.com"},
			{"forced_login_method = \"api\"\n", Map.of(), null, "api.openai.com"},
			{"", Map.of(), "{\"auth_mode\": \"apikey\", \"OPENAI_API_KEY\": \"sk-test\"}", "api.openai.com"},
			{"", Map.of(), "{\"auth_mode\": \"chatgpt\", \"OPENAI_API_KEY\": null}", "chatgpt.com"},
			{"", Map.of(), null, "chatgpt.com"}
		};
	}

	/**
	 * Verifies that a session's model host is the host of the model provider's base URL that the session copies,
	 * otherwise the host that Codex reaches for the session's login.
	 *
	 * @param configuration the user's Codex configuration
	 * @param environment   the runner's environment
	 * @param login         the session's login file, or {@code null} if it has none
	 * @param host          the expected model host
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "modelHosts")
	public void findsModelHost(String configuration, Map<String, String> environment, String login, String host)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-model-host-"));
			Path source = Files.writeString(root.resolve("host.toml"), configuration);
			Path loginFile = root.resolve("auth.json");
			if (login != null)
				Files.writeString(loginFile, login);

			requireThat(CodexConnectionConfiguration.modelHost(source, environment, loginFile), "modelHost").
				isEqualTo(host);
		}
	}

	/**
	 * Verifies a nested Codex shell uses the verified dependency caches instead of default caches that its sandbox may
	 * expose read-only, and may write to them.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void configuresNestedDependencyCaches() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-npm-cache-"));
			Path npmCache = Files.createDirectories(root.resolve("npm-cache"));
			Path mavenCache = Files.createDirectories(root.resolve("maven-cache"));
			String mavenOptions = "-Dmaven.repo.local=" + mavenCache.resolve("repository");
			HarnessDependencyCaches caches = new HarnessDependencyCaches(List.of(npmCache, mavenCache),
				Map.of("NPM_CONFIG_CACHE", npmCache.toString(), "MAVEN_OPTS", mavenOptions));
			Path destination = root.resolve("isolated/config.toml");
			Path temporaryDirectory = root.resolve("isolated/tmp");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, root.resolve("project"),
				caches, temporaryDirectory);

			String configuration = Files.readString(destination);
			requireThat(configuration, "configuration").contains("[sandbox_workspace_write]").
				contains("writable_roots = [\"" + toml(npmCache) + "\", \"" + toml(mavenCache) + "\"]").
				contains("[shell_environment_policy.set]").
				contains("NPM_CONFIG_CACHE = \"" + toml(npmCache) + "\"").
				contains("MAVEN_OPTS = \"" + mavenOptions.replace("\\", "\\\\") + "\"").
				contains("TMPDIR = \"" + toml(temporaryDirectory) + "\"").
				contains("\"" + toml(npmCache) + "\" = \"write\"").
				contains("\"" + toml(mavenCache) + "\" = \"write\"");
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

			CodexConnectionConfiguration.copy(source, limited, root.resolve("project"), HarnessDependencyCaches.NONE,
				root.resolve("limited/tmp"), 5000, Platform.current());
			CodexConnectionConfiguration.copy(source, unlimited, root.resolve("project"), HarnessDependencyCaches.NONE,
				root.resolve("unlimited/tmp"), 0, Platform.current());

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
	 * Verifies that an isolated Codex process receives none of the host's permission profiles, which grant the
	 * host's own directories, so that it can neither write nor learn the paths of the host's Codex home, CAT user
	 * directory or other workspaces.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void omitsHostPermissionProfiles() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-permissions-"));
			Path source = root.resolve("host.toml");
			Path destination = root.resolve("isolated/config.toml");
			Files.writeString(source, """
				[permissions.cat]
				extends = ":workspace"
				[permissions.cat.workspace_roots]
				"/host/worktrees" = true
				[permissions.cat.filesystem]
				"/host/.codex" = "write"
				[permissions.cat.filesystem.":workspace_roots"]
				"." = "write"
				[permissions.other]
				"/secret" = "write"
				""");

			CodexConnectionConfiguration.copy(source, destination, root.resolve("project"));

			requireThat(Files.readString(destination), "configuration").doesNotContain("[permissions.cat]").
				doesNotContain("[permissions.cat.").doesNotContain("/host/").doesNotContain("[permissions.other]").
				contains("[permissions.cat-runner]" + System.lineSeparator() + "extends = \":workspace\"");
		}
	}

	/**
	 * Verifies that a session may write a dependency cache inside its parent's writes, may not write what its parent
	 * may not write, and may not read what its parent may not read.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void filesystemFollowsPolicy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-filesystem-policy-")).toRealPath();
			Path parentWrites = Files.createDirectories(root.resolve("parent-writes"));
			Path keptCache = Files.createDirectories(parentWrites.resolve("npm-cache"));
			Path runFolder = Files.createDirectories(root.resolve("run"));
			Path protectedFolder = Files.createDirectories(runFolder.resolve("home/protected"));
			Path secret = Files.createDirectories(root.resolve("secret"));
			HarnessDependencyCaches caches = new HarnessDependencyCaches(List.of(keptCache), Map.of());
			ParentPolicy parent = new ParentPolicy(true, List.of(parentWrites, runFolder), List.of(protectedFolder),
				List.of(secret), List.of(), HostAccess.NONE, false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(runFolder, caches.directories(),
				List.of(), Set.of()));
			Path destination = runFolder.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, runFolder.resolve("project"),
				caches, runFolder.resolve("home/tmp"), 0, null, Platform.LINUX_X64, CodexParentMultiAgentState.NONE,
				policy);

			JsonNode configuration = TomlMapper.builder().build().readTree(Files.readString(destination));
			JsonNode filesystem = configuration.path("permissions").path("cat-runner").path("filesystem");
			requireThat(filesystem.path(keptCache.toString()).asString(), "keptCache").isEqualTo("write");
			requireThat(filesystem.path(protectedFolder.toString()).asString(), "protectedFolder").isEqualTo("read");
			requireThat(filesystem.path(secret.toString()).asString(), "secret").isEqualTo("deny");
		}
	}

	/**
	 * Verifies that the session's commands may write its isolated home, except the files its own Codex app server
	 * executes or trusts: the configuration, the hooks file beside it, and the plugin cache that holds the hook
	 * scripts. The session still writes the rest of its home and its temporary directory.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void codexChildCannotChangeItsHooks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-trusted-files-")).toRealPath();
			Path runFolder = Files.createDirectories(root.resolve("run"));
			Path home = runFolder.resolve("home");
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(runFolder, List.of(),
				List.of(), Set.of()));
			Path destination = home.resolve("config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, runFolder.resolve("project"),
				HarnessDependencyCaches.NONE, home.resolve("tmp"), 0, null, Platform.LINUX_X64,
				CodexParentMultiAgentState.NONE, policy);

			JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-runner").path("filesystem");
			requireThat(filesystem.path(home.toString()).asString(), "home").isEqualTo("write");
			requireThat(filesystem.path(home.resolve("config.toml").toString()).asString(), "configuration").
				isEqualTo("read");
			requireThat(filesystem.path(home.resolve("hooks.json").toString()).asString(), "hooks").
				isEqualTo("read");
			requireThat(filesystem.path(home.resolve("plugins").toString()).asString(), "plugins").
				isEqualTo("read");
			requireThat(filesystem.path(":tmpdir").asString(), "temporaryDirectory").isEqualTo("write");
			requireThat(filesystem.has(home.resolve("outputs").toString()), "runOutputs").isFalse();
		}
	}

	/**
	 * Verifies that a read denial inside another read denial is left to the outer one, since Codex's Linux sandbox
	 * cannot hide a file inside a folder that it already hides.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void nestedReadDenialIsLeftToTheOuterOne() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-nested-denial-")).toRealPath();
			Path runFolder = Files.createDirectories(root.resolve("run"));
			Path secret = Files.createDirectories(root.resolve("secret"));
			Path inner = Files.writeString(secret.resolve("hidden.txt"), "private");
			ParentPolicy parent = new ParentPolicy(true, List.of(runFolder), List.of(), List.of(inner, secret),
				List.of(), HostAccess.NONE, false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(runFolder, List.of(), List.of(),
				Set.of()));
			Path destination = runFolder.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, runFolder.resolve("project"),
				HarnessDependencyCaches.NONE, runFolder.resolve("home/tmp"), 0, null, Platform.LINUX_X64,
				CodexParentMultiAgentState.NONE, policy);

			JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-runner").path("filesystem");
			requireThat(filesystem.path(secret.toString()).asString(), "secret").isEqualTo("deny");
			requireThat(filesystem.has(inner.toString()), "innerDenial").isFalse();
		}
	}

	/**
	 * Verifies that a read denial inside another read denial stays denied when the session may write a folder between
	 * them, since that grant, being the more specific entry, would otherwise let the session read it.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void regrantedNestedDenialStays() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-regranted-denial-")).toRealPath();
			Path runFolder = Files.createDirectories(root.resolve("run"));
			Path secret = Files.createDirectories(root.resolve("secret"));
			Path home = Files.createDirectories(secret.resolve("home"));
			Path login = Files.writeString(Files.createDirectories(home.resolve(".claude")).
				resolve(".credentials.json"), "{}");
			ParentPolicy parent = new ParentPolicy(true, List.of(runFolder), List.of(), List.of(secret, login),
				List.of(), HostAccess.NONE, false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(runFolder, List.of(), List.of(),
				Set.of()));
			Path destination = home.resolve("config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, runFolder.resolve("project"),
				HarnessDependencyCaches.NONE, home.resolve("tmp"), 0, null, Platform.LINUX_X64,
				CodexParentMultiAgentState.NONE, policy);

			JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-runner").path("filesystem");
			requireThat(filesystem.path(home.toString()).asString(), "home").isEqualTo("write");
			requireThat(filesystem.path(secret.toString()).asString(), "secret").isEqualTo("deny");
			requireThat(filesystem.path(login.toString()).asString(), "login").isEqualTo("deny");
		}
	}

	/**
	 * Verifies that a session's read denial inside another of its read denials stays denied when its runner profile
	 * lets it write a folder between them, as the run's home lies inside the denied run root.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void regrantedSessionDenialStays() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-regranted-denial-")).toRealPath();
			Path runs = Files.createDirectories(root.resolve("runs"));
			Path home = Files.createDirectories(runs.resolve("run/home"));
			Path login = Files.writeString(Files.createDirectories(home.resolve(".claude")).
				resolve(".credentials.json"), "{}");
			Path trials = Files.createDirectories(runs.resolve("trials"));
			Path destination = home.resolve("config.toml");
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, runs.resolve("run/project"));

			CodexConnectionConfiguration.addSessionProfile(destination, List.of(), List.of(runs, trials, login));

			JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-denied-reads").path("filesystem");
			requireThat(filesystem.path(runs.toString()).asString(), "runs").isEqualTo("deny");
			requireThat(filesystem.path(login.toString()).asString(), "login").isEqualTo("deny");
			requireThat(filesystem.has(trials.toString()), "trials").isFalse();
		}
	}

	/**
	 * Returns Codex configuration layers that would give a session more than its policy allows, and the setting that
	 * each names.
	 *
	 * @return whether the layer is the managed one, its content and the setting that the refusal names
	 */
	@DataProvider
	public Object[][] wideningLayers()
	{
		return new Object[][]
		{
			{false, "[permissions.cat-runner.filesystem]\n\"/\" = \"write\"\n",
				"permissions.cat-runner.filesystem"},
			{false, "[permissions.cat-runner.network.domains]\n\"example.com\" = \"allow\"\n",
				"permissions.cat-runner.network.domains"},
			{false, "[permissions.cat-runner.network]\nallow_local_binding = true\n",
				"permissions.cat-runner.network.allow_local_binding"},
			{true, "[permissions.cat-runner.network]\nenabled = true\n", "permissions.cat-runner.network.enabled"},
			{true, "default_permissions = \":danger-no-sandbox\"\n", "default_permissions"},
			{true, "sandbox_mode = \"danger-full-access\"\n", "sandbox_mode"},
			{true, "[permissions.cat-runner]\nextends = \":danger-no-sandbox\"\n", "permissions.cat-runner.extends"},
			{true, "[permissions.cat-denied-reads.filesystem]\n\":root\" = \"write\"\n",
				"permissions.cat-denied-reads.filesystem"},
			{true, "not toml", ""}
		};
	}

	/**
	 * Verifies that the runner refuses to start a session whose system or managed Codex configuration layer, which
	 * Codex merges into the session's own configuration, would give it more than its policy allows, and names the
	 * layer and the setting.
	 *
	 * @param managed {@code true} if the layer is the managed one
	 * @param layer   the layer's content
	 * @param setting the setting that the refusal names
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "wideningLayers")
	public void refusesWideningLayer(boolean managed, String layer, String setting) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-widening-layer-"));
			Path system = root.resolve("config.toml");
			Path managedLayer = root.resolve("managed_config.toml");
			Path file = system;
			if (managed)
				file = managedLayer;
			Files.writeString(file, layer);
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(root.resolve("run"),
				List.of(), List.of(), Set.of()));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> CodexConnectionConfiguration.requireNarrowingLayers(system, managedLayer, policy));

			requireThat(refusal.getMessage(), "message").contains(file.toString()).contains(setting);
		}
	}

	/**
	 * Verifies that the runner starts a session whose system and managed Codex configuration layers only narrow it,
	 * or that has none.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void acceptsNarrowingLayers() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-narrowing-layer-"));
			Path run = Files.createDirectories(root.resolve("run"));
			Path system = Files.writeString(root.resolve("config.toml"), """
				model = "gpt-6-sol"
				sandbox_mode = "workspace-write"
				[permissions.cat-runner.filesystem]
				"%s" = "write"
				"/secret" = "deny"
				"/elsewhere" = "none"
				[permissions.cat-runner.network.domains]
				"chatgpt.com" = "allow"
				"example.com" = "deny"
				[features]
				fast_mode = false
				""".formatted(toml(run.resolve("home"))));
			Path managed = root.resolve("managed_config.toml");
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(run, List.of(),
				List.of(), Set.of("chatgpt.com")));

			CodexConnectionConfiguration.requireNarrowingLayers(system, managed, policy);
			CodexConnectionConfiguration.requireNarrowingLayers(root.resolve("missing.toml"), managed, policy);
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
				contains("extends = \"cat-runner\"").contains("\"" + toml(canonicalEvidence) + "\" = \"read\"").
				doesNotContain("\"" + toml(canonicalEvidence) + "\" = \"write\"");
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
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, root.resolve("project"));

			String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(),
				List.of(snapshot, trials));

			requireThat(profile, "profile").isEqualTo("cat-denied-reads");
			requireThat(Files.readString(destination), "configuration").contains(String.join(System.lineSeparator(),
				"[permissions.cat-denied-reads]",
				"extends = \"cat-runner\"",
				"[permissions.cat-denied-reads.filesystem]",
				"\"" + toml(snapshot.toRealPath()) + "\" = \"deny\"",
				"\"" + toml(trials.toRealPath()) + "\" = \"deny\"",
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
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, root.resolve("project"));

			String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(evidence),
				List.of(snapshot));

			requireThat(profile, "profile").isEqualTo("cat-denied-reads");
			requireThat(Files.readString(destination), "configuration").
				contains("\"" + toml(evidence.toRealPath()) + "\" = \"read\"").
				contains(String.join(System.lineSeparator(),
					"[permissions.cat-denied-reads]",
					"extends = \"cat-validator\"",
					"[permissions.cat-denied-reads.filesystem]",
					"\"" + toml(snapshot.toRealPath()) + "\" = \"deny\"",
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
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, root.resolve("project"));
			String before = Files.readString(destination);

			String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(), List.of());

			requireThat(profile, "profile").isEmpty();
			requireThat(Files.readString(destination), "configuration").isEqualTo(before);
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

	/**
	 * Verifies the user's approvals of a plugin's hooks reach the isolated configuration under the run-local
	 * marketplace that bears the plugin's name, and that no other plugin's approvals do.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesTrustedPluginHooks() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-hook-trust-"));
			Path source = root.resolve("host.toml");
			Path destination = root.resolve("isolated/config.toml");
			Files.writeString(source, """
				[hooks.state."release-docs@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:c29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"
				enabled = true

				[hooks.state."release-docs-extra@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:b29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"

				[hooks.state."cat@cat:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:d29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"
				""");

			CodexConnectionConfiguration.copyTrustedHooks(source, destination, "release-docs");

			String configuration = Files.readString(destination);
			requireThat(configuration, "configuration").contains(
				"[hooks.state.\"release-docs@release-docs:hooks/hooks.json:session_start:0:0\"]").contains(
					"trusted_hash = \"sha256:c29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3\"").
				doesNotContain("release-docs-extra").doesNotContain("cat@cat").doesNotContain("enabled = true");
		}
	}

	/**
	 * Copies a host configuration into an isolated one under a session policy, as a Linux session without
	 * dependency caches, requests or a starting Codex thread.
	 *
	 * @param source      the host configuration file
	 * @param destination the isolated configuration file
	 * @param project     the copied project
	 * @param policy      the session's policy
	 * @throws IOException if configuration I/O fails
	 */
	private static void copy(Path source, Path destination, Path project, ChildPolicy policy) throws IOException
	{
		CodexConnectionConfiguration.copy(source, destination, project, HarnessDependencyCaches.NONE,
			destination.resolveSibling("tmp"), 0, null, Platform.LINUX_X64, CodexParentMultiAgentState.NONE, policy);
	}

	/**
	 * Returns a path as the content of a TOML basic string, escaping the separators of a Windows path.
	 *
	 * @param path a path
	 * @return the escaped path
	 */
	private static String toml(Path path)
	{
		return path.toString().replace("\\", "\\\\");
	}
}
