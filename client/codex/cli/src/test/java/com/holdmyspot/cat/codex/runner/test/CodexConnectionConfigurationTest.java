package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.optimizer.InstalledPlugins;
import com.holdmyspot.cat.codex.runner.CodexAuthentication;
import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexParentMultiAgentState;
import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Tests {@link CodexConnectionConfiguration}.
 */
public final class CodexConnectionConfigurationTest
{
	/**
	 * Verifies that refreshing an interrupted session keeps its own registrations and trust while replacing its former
	 * connection and permissions with the current parent's settings.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void resumedConfigurationKeepsPluginsAndTrust() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-resume-")).toRealPath();
			Path session = Files.createDirectories(root.resolve("session"));
			Path project = Files.createDirectory(session.resolve("project"));
			Path home = Files.createDirectory(session.resolve("home"));
			Path cat = Files.createDirectories(home.resolve("plugins/cache/cat/cat/1/.codex-plugin"));
			Files.writeString(cat.resolve("plugin.json"), "{}");
			Path trial = Files.createDirectories(home.resolve("plugins/cache/trial/trial/1/.codex-plugin"));
			Files.writeString(trial.resolve("plugin.json"), "{}");
			Path destination = home.resolve("config.toml");
			Path source = Files.writeString(root.resolve("parent.toml"), """
				model = "current-model"
				[plugins."host@host"]
				enabled = true
				""");
			JsonMapper json = JsonMapper.builder().build();
			String catHook = "cat@cat:hooks/hooks.json:session_start:0:0";
			String trialHook = "hooks/hooks.json:post_tool_use:0:0";
			String catHash = "sha256:" + "b".repeat(64);
			String trialHash = "sha256:" + "c".repeat(64);
			Files.writeString(destination, """
				model = "old-model"
				tool_output_token_limit = 99
				[plugins."cat@cat"]
				enabled = true
				[plugins."trial@trial"]
				enabled = true
				[plugins."trial@trial".mcp_servers.trial-server]
				enabled_tools = ["one", "two"]
				[marketplaces.cat]
				source = %s
				source_type = "local"
				[marketplaces.trial]
				source = %s
				source_type = "local"
				[hooks.state."%s"]
				trusted_hash = "%s"
				[hooks.state."trial@trial:%s"]
				trusted_hash = "%s"
				enabled = false
				[projects.%s]
				trust_level = "trusted"
				[permissions.cat-runner.network.domains]
				"old-host.example" = "allow"
				[shell_environment_policy.set]
				CAT_OLD_REQUEST_TOKEN = "old-token"
				""".formatted(json.writeValueAsString(home.resolve("plugins/cat-marketplace").toString()),
				json.writeValueAsString(home.resolve("plugins/trial-marketplace").toString()), catHook, catHash, trialHook,
				trialHash, json.writeValueAsString(project.toString())));
			TomlMapper toml = TomlMapper.builder().build();
			JsonNode prior = toml.readTree(Files.readString(destination));
			Path secret = Files.writeString(root.resolve("secret"), "private");
			Path deniedRead = Files.writeString(root.resolve("denied-read"), "private");
			ParentPolicy parent = new ParentPolicy(true, List.of(session), List.of(), List.of(secret), List.of(),
				HostAccess.listed(Set.of("current-host.example")), false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(session, List.of(), List.of(),
				Set.of("current-host.example")));

			// Reopen both the original configuration and one already refreshed by the runner.
			Path staged = home.resolve("staged.toml");
			for (int attempt = 0; attempt < 2; ++attempt)
			{
				CodexConnectionConfiguration.copy(source, staged, project, HarnessDependencyCaches.NONE,
					home.resolve("tmp"), 42, null, Platform.LINUX_X64, CodexParentMultiAgentState.NONE, policy,
					ExtraPermissions.NONE, destination);
				CodexConnectionConfiguration.addSessionProfile(staged, List.of(), List.of(deniedRead));
				Yielding.fileReplacer().replace(staged, destination);
			}

			requireThat(CodexRunner.installedCat(home), "catInstallation").isEqualTo(cat.getParent());
			requireThat(InstalledPlugins.of(home, "trial@trial"), "trialInstallation").isEqualTo(List.of(trial.getParent()));
			requireThat(InstalledPlugins.names(home), "pluginNames").isEqualTo(List.of("cat@cat", "trial@trial"));
			requireThat(CodexConnectionConfiguration.pluginHookApprovals(destination, "trial"), "trialApprovals").
				isEqualTo(Map.of(trialHook, trialHash));
			Path childConfiguration = home.resolve("child.toml");
			CodexConnectionConfiguration.copyTrustedCatHooks(destination, childConfiguration);
			JsonNode appliedCatTrust = toml.readTree(Files.readString(childConfiguration)).path("hooks").path("state");
			requireThat(appliedCatTrust.path(catHook).path("trusted_hash").asString(), "catApproval").isEqualTo(catHash);
			JsonNode refreshed = toml.readTree(Files.readString(destination));
			for (String table : List.of("plugins", "marketplaces", "projects"))
				requireThat(refreshed.path(table), table).isEqualTo(prior.path(table));
			requireThat(refreshed.path("hooks").path("state"), "hookTrust").isEqualTo(prior.path("hooks").path("state"));
			requireThat(refreshed.path("model").asString(), "model").isEqualTo("current-model");
			requireThat(refreshed.path("tool_output_token_limit").asInt(), "toolOutputLimit").isEqualTo(42);
			JsonNode profile = refreshed.path("permissions").path("cat-runner");
			requireThat(profile.path("network").path("domains").path("current-host.example").asString(), "currentHost").
				isEqualTo("allow");
			requireThat(profile.path("network").path("domains").has("old-host.example"), "oldHost").isFalse();
			requireThat(profile.path("filesystem").path(secret.toString()).asString(), "currentDenial").isEqualTo("deny");
			requireThat(refreshed.path("permissions").path("cat-denied-reads").path("filesystem").
				path(deniedRead.toString()).asString(), "additionalDenial").isEqualTo("deny");
			requireThat(refreshed.path("shell_environment_policy").path("set").has("CAT_OLD_REQUEST_TOKEN"),
				"oldRequestToken").isFalse();
		}
	}

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
	 * Verifies that a session that another session requests, which its requester's policy bounds with a denial of the
	 * folder that holds the requester's requested sessions, has a permission profile that denies its sibling sessions
	 * but not its own responses directory, where its commands read the responses to their own requests.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void profileKeepsOwnResponsesReadable() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-own-responses-")).toRealPath();
			Path requestedSessions = Files.createDirectories(root.resolve("slots/d1/codex/s-1/slots"));
			Path session = Files.createDirectories(requestedSessions.resolve("d1/codex/s-1"));
			Path sibling = Files.createDirectories(requestedSessions.resolve("d1/codex/s-2/project"));
			Path project = Files.createDirectories(session.resolve("project"));
			Files.createDirectories(session.resolve("home"));
			Path requests = Files.createDirectories(session.resolve("requests"));
			Path responses = Files.createDirectories(session.resolve("responses"));
			ParentPolicy bound = new ParentPolicy(true, List.of(requests), List.of(), List.of(requestedSessions),
				List.of(), HostAccess.listed(Set.of("model.example")), false, List.of());
			SessionRequestAccess access = new SessionRequestAccess(requests, responses, root.resolve("requested-results"),
				root.resolve("artifact/bin/request-session"), SessionRequestAccess.Shell.POSIX);
			ChildPolicy policy = CodexRunner.sessionPolicy(bound, new ChildNeeds(session, List.of(), List.of(),
				Set.of("model.example")), access);
			Path destination = root.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, project,
				HarnessDependencyCaches.NONE, root.resolve("tmp"), 0, access, Platform.LINUX_X64,
				CodexParentMultiAgentState.NONE, policy);

			JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-runner").path("filesystem");
			List<String> denied = new ArrayList<>();
			for (Map.Entry<String, JsonNode> entry : filesystem.properties())
			{
				if (entry.getValue().asString().equals("deny"))
					denied.add(entry.getKey());
			}
			requireThat(covers(denied, responses), "deniesOwnResponses").withContext(denied, "denied").isFalse();
			requireThat(covers(denied, sibling), "deniesSibling").withContext(denied, "denied").isTrue();
		}
	}

	/**
	 * Verifies that the policy that a nested session's runner reports for a session that installs CAT keeps the
	 * installed plugin out of the session's writable paths, as the session's profile does, so that a check program
	 * that the session's driver starts runs on a runtime that the session cannot change, and that the rest of the
	 * session's home stays writable.
	 *
	 * @throws IOException   if fixture I/O fails
	 * @throws PolicyRefusal if the policy would let the session change the runtime
	 */
	@Test
	public void reportsInstalledPluginUnwritable() throws IOException, PolicyRefusal
	{
		// Requirement: R-L21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugin-policy-")).toRealPath();
			Path session = Files.createDirectories(root.resolve("session"));
			Path codexFolder = Files.createDirectories(session.resolve("home/.codex"));
			Path runtime = Files.createDirectories(codexFolder.resolve("plugins/cache/cat/cat/1.0/bin/runtime"));
			Path scratch = Files.createDirectories(session.resolve("home/scratch"));
			ParentPolicy bound = new ParentPolicy(true, List.of(root), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of("model.example")), false, List.of());

			ChildPolicy policy = CodexRunner.sessionPolicy(bound, new ChildNeeds(session, List.of(), List.of(),
				Set.of("model.example")), null, codexFolder);

			ParentPolicy owner = policy.asParentPolicy();
			requireThat(owner.mayWrite(runtime), "mayWriteRuntime").isFalse();
			requireThat(owner.mayWrite(scratch), "mayWriteScratch").isTrue();
			owner.requireTrustedTree(runtime);
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
	 * Verifies that every path that the runner profile of a fresh session makes read-only exists before Codex starts.
	 * Codex keeps a writable root's {@code .codex} folder read-only, so its sandbox cannot create a placeholder for a
	 * missing read entry there.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readEntriesExistInFreshSessionHome() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-read-entries-")).toRealPath();
			Path session = Files.createDirectories(root.resolve("session"));
			Files.createDirectory(session.resolve("project"));
			Path home = Files.createDirectory(session.resolve("home"));
			Path codexFolder = Files.createDirectory(home.resolve(CodexAuthentication.FOLDER));
			Path destination = codexFolder.resolve("config.toml");

			copyAsRunnerStarts(root.resolve("missing.toml"), destination, session, null);

			List<String> missing = missingReadEntries(destination);
			requireThat(missing, "missing").isEmpty();
		}
	}

	/**
	 * Verifies that every path that the runner profile makes read-only exists before Codex starts, after a resume
	 * moves the Codex files of a session that an earlier runner started, without plugins, into the home's
	 * {@code .codex} folder.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readEntriesExistAfterEarlierLayoutMove() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-read-entries-")).toRealPath();
			Path session = Files.createDirectories(root.resolve("session"));
			Files.createDirectory(session.resolve("project"));
			Path home = Files.createDirectory(session.resolve("home"));
			Files.writeString(home.resolve("config.toml"), "model = \"earlier-model\"\n");
			Files.writeString(home.resolve("hooks.json"), "{\"hooks\":{}}\n");
			CodexRunner.moveEarlierLayout(home);
			Path codexFolder = home.resolve(CodexAuthentication.FOLDER);
			Path retained = codexFolder.resolve("config.toml");
			Path staged = Files.createTempFile(codexFolder, ".config-", ".toml");

			copyAsRunnerStarts(root.resolve("missing.toml"), staged, session, retained);

			List<String> missing = missingReadEntries(staged);
			requireThat(missing, "missing").isEmpty();
		}
	}

	/**
	 * Writes a session's configuration as the runner does when it starts the session, with the session's Codex
	 * folder in its home's {@code .codex} folder.
	 *
	 * @param source      the source home's configuration
	 * @param destination the session's configuration, inside the session's Codex folder
	 * @param session     the session folder, which holds the {@code home} and {@code project} folders
	 * @param retained    the configuration that a resumed session keeps its registrations from, or {@code null}
	 * @throws IOException if the configuration cannot be written
	 */
	private static void copyAsRunnerStarts(Path source, Path destination, Path session, Path retained)
		throws IOException
	{
		Path home = session.resolve("home");
		ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(session, List.of(),
			List.of(), Set.of()));
		CodexConnectionConfiguration.copy(source, destination, home, "", session.resolve("project"),
			HarnessDependencyCaches.NONE, Files.createDirectories(home.resolve("tmp")), 0, null, Platform.LINUX_X64,
			CodexParentMultiAgentState.NONE, policy, ExtraPermissions.NONE, retained);
	}

	/**
	 * Returns the read-only paths of a configuration's runner profile that do not exist.
	 *
	 * @param configuration the session's configuration
	 * @return the paths
	 * @throws IOException if the configuration cannot be read
	 */
	private static List<String> missingReadEntries(Path configuration) throws IOException
	{
		JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(configuration)).
			path("permissions").path("cat-runner").path("filesystem");
		requireThat(filesystem.isEmpty(), "noRunnerProfile").isFalse();
		List<String> missing = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : filesystem.properties())
		{
			if (entry.getValue().asString().equals("read") && !Files.exists(Path.of(entry.getKey())))
				missing.add(entry.getKey());
		}
		return missing;
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
	 * Verifies an isolated Codex configuration retains the runner's model settings but neither its login method nor
	 * its base URL, which come from the source home, and excludes host customizations.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void copiesOnlyConnectionSettings() throws IOException
	{
		// Requirement: R-I15
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
				doesNotContain("openai_base_url").doesNotContain("forced_login_method").contains("model =").
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
		// Requirement: R-I11
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
		// Requirement: R-I11
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
		// Requirement: R-I11
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
	 * Returns source Codex settings and the login that each names as the session's model host.
	 *
	 * @return the source configuration, the source login file's content or {@code null} when there is none, and the
	 *   model host
	 */
	@DataProvider
	public Object[][] modelHosts()
	{
		String apiKey = "{\"auth_mode\": \"apikey\", \"OPENAI_API_KEY\": \"" + CodexSourceHomes.SECRET + "\"}";
		String signIn = "{\"auth_mode\": \"chatgpt\", \"OPENAI_API_KEY\": null, \"tokens\": {}}";
		return new Object[][]
		{
			{"openai_base_url = \"http://LocalHost:2455/backend-api/codex\"\n", apiKey, "localhost"},
			{"""
				model_provider = "gateway"
				openai_base_url = "https://ignored.example/v1"
				[model_providers.gateway]
				base_url = "https://Gateway.Example/v1"
				""", null, "gateway.example"},
			{"", apiKey, "api.openai.com"},
			{"forced_login_method = \"api\"\n", apiKey, "api.openai.com"},
			{"", signIn, "chatgpt.com"},
			{"", null, "chatgpt.com"}
		};
	}

	/**
	 * Verifies that a session's model host is the host of the base URL of the source's selected provider, otherwise
	 * of the source's {@code openai_base_url}, otherwise the host that Codex reaches for the source's login.
	 *
	 * @param configuration the source's Codex configuration
	 * @param login         the source's login file, or {@code null} if it has none
	 * @param host          the expected model host
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "modelHosts")
	public void findsModelHost(String configuration, String login, String host) throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-model-host-"));
			Path source = Files.writeString(root.resolve("config.toml"), configuration);
			Path loginFile = root.resolve("auth.json");
			if (login != null)
				Files.writeString(loginFile, login);

			requireThat(CodexConnectionConfiguration.modelHost(source, loginFile), "modelHost").isEqualTo(host);
		}
	}

	/**
	 * Verifies that the session's configuration holds the source's {@code model_provider}, {@code openai_base_url},
	 * {@code forced_login_method} and selected provider's table, with the provider's token command wrapped so that it
	 * runs with the source home as its home folder, and none of the source's hooks, MCP servers, profiles or other
	 * providers, nor the runner's own login settings; and that the wrapped command, run with another home folder,
	 * prints the key that the source's command reads from {@code ~/key}.
	 *
	 * @throws IOException          if fixture I/O or the command fails
	 * @throws InterruptedException if the test is interrupted while the command runs
	 */
	@Test
	public void copiesSourceProviderWithWrappedCommand() throws IOException, InterruptedException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-source-provider-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.secret(source.resolve("key"));
			CodexSourceHomes.configuration(source.resolve(".codex"), """
				model_provider = "gateway"
				openai_base_url = "https://openai-proxy.example/v1"
				forced_login_method = "api"
				[model_providers.gateway]
				name = "Gateway"
				base_url = "https://gateway.example/v1"
				wire_api = "responses"
				[model_providers.gateway.auth]
				command = "sh"
				args = ["-c", "cat ~/key"]
				[model_providers.other]
				base_url = "https://other.example/v1"
				[hooks.state]
				"source-hook" = "trusted"
				[mcp_servers.source-server]
				command = "source-server"
				[profiles.source-profile]
				model = "source-model"
				""");
			Path host = Files.writeString(root.resolve("host.toml"), """
				openai_base_url = "https://runner.example/v1"
				forced_login_method = "chatgpt"
				""");
			CodexAuthentication authentication = CodexAuthentication.read(new AuthenticationOptions(Optional.of(source),
				Optional.of(HostLogin.API_KEY), false), Map.of(), Platform.current());
			Path home = Files.createDirectories(root.resolve("session/home"));
			Path destination = home.resolve(".codex/config.toml");
			Files.createDirectories(destination.getParent());
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(root.resolve("session"),
				List.of(), List.of(), Set.of()));

			CodexConnectionConfiguration.copy(host, destination, home, authentication.settings(home.resolve(".codex")),
				root.resolve("session/project"), HarnessDependencyCaches.NONE, home.resolve("tmp"), 0, null,
				Platform.LINUX_X64, CodexParentMultiAgentState.NONE, policy, ExtraPermissions.NONE, null);

			String text = Files.readString(destination);
			JsonNode configuration = TomlMapper.builder().build().readTree(text);
			requireThat(configuration.path("model_provider").asString(""), "modelProvider").isEqualTo("gateway");
			requireThat(configuration.path("openai_base_url").asString(""), "openaiBaseUrl").
				isEqualTo("https://openai-proxy.example/v1");
			requireThat(configuration.path("forced_login_method").asString(""), "forcedLoginMethod").isEqualTo("api");
			List<String> providers = new ArrayList<>(configuration.path("model_providers").propertyNames());
			requireThat(providers, "providers").isEqualTo(List.of("gateway"));
			JsonNode gateway = configuration.path("model_providers").path("gateway");
			requireThat(gateway.path("base_url").asString(""), "baseUrl").isEqualTo("https://gateway.example/v1");
			requireThat(gateway.path("wire_api").asString(""), "wireApi").isEqualTo("responses");
			for (String setting : List.of("mcp_servers", "profiles"))
				requireThat(configuration.has(setting), setting).isFalse();
			requireThat(text, "configuration").doesNotContain("source-hook").doesNotContain("runner.example").
				doesNotContain(CodexSourceHomes.SECRET);

			List<String> command = new ArrayList<>();
			command.add(gateway.path("auth").path("command").asString(""));
			for (JsonNode argument : gateway.path("auth").path("args"))
				command.add(argument.asString(""));
			requireThat(command, "command").contains("cat ~/key");
			Path otherHome = Files.createDirectories(root.resolve("other-home"));
			ProcessBuilder shell = new ProcessBuilder("sh", "-c", "exec \"$0\" \"$@\"");
			shell.command().addAll(command);
			shell.redirectErrorStream(true).environment().put("HOME", otherHome.toString());
			try (Process process = shell.start())
			{
				String printed = new String(process.getInputStream().readAllBytes(), UTF_8);
				requireThat(process.waitFor(), "status").isZero();
				requireThat(printed, "printed").isEqualTo(CodexSourceHomes.SECRET);
			}
		}
	}

	/**
	 * Returns system or managed Codex configuration layers that set a setting which would replace the source home's
	 * credential or model host, and the setting that each sets.
	 *
	 * @return whether the layer is the managed one, its content and the setting that the refusal names
	 */
	@DataProvider
	public Object[][] credentialLayers()
	{
		List<Object[]> layers = new ArrayList<>();
		for (boolean managed : List.of(false, true))
		{
			layers.add(new Object[]{managed, "openai_base_url = \"https://layer.example/v1\"\n", "openai_base_url"});
			layers.add(new Object[]{managed, "forced_login_method = \"api\"\n", "forced_login_method"});
			layers.add(new Object[]{managed, "model_provider = \"layer\"\n", "model_provider"});
			layers.add(new Object[]{managed, "[model_providers.layer]\nbase_url = \"https://layer.example/v1\"\n",
				"model_providers"});
		}
		return layers.toArray(Object[][]::new);
	}

	/**
	 * Verifies that the runner refuses to start a session whose system or managed Codex configuration layer sets a
	 * model provider, its base URL or the login method, which would replace the source home's credential or model
	 * host, and names the layer and the setting.
	 *
	 * @param managed {@code true} if the layer is the managed one
	 * @param layer   the layer's content
	 * @param setting the setting that the refusal names
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "credentialLayers")
	public void refusesCredentialLayer(boolean managed, String layer, String setting) throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-credential-layer-"));
			Path system = root.resolve("config.toml");
			Path managedLayer = root.resolve("managed_config.toml");
			Path file = system;
			if (managed)
				file = managedLayer;
			Files.writeString(file, layer);
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(root.resolve("session"),
				List.of(), List.of(), Set.of()));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
				() -> CodexConnectionConfiguration.requireNarrowingLayers(system, managedLayer, policy));

			requireThat(refusal.getMessage(), "message").contains(file.toString()).contains(setting);
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-filesystem-policy-")).toRealPath();
			Path parentWrites = Files.createDirectories(root.resolve("parent-writes"));
			Path keptCache = Files.createDirectories(parentWrites.resolve("npm-cache"));
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path protectedFolder = Files.createDirectories(sessionFolder.resolve("home/protected"));
			Path secret = Files.createDirectories(root.resolve("secret"));
			HarnessDependencyCaches caches = new HarnessDependencyCaches(List.of(keptCache), Map.of());
			ParentPolicy parent = new ParentPolicy(true, List.of(parentWrites, sessionFolder), List.of(protectedFolder),
				List.of(secret), List.of(), HostAccess.NONE, false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(sessionFolder, caches.directories(),
				List.of(), Set.of()));
			Path destination = sessionFolder.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, sessionFolder.resolve("project"),
				caches, sessionFolder.resolve("home/tmp"), 0, null, Platform.LINUX_X64, CodexParentMultiAgentState.NONE,
				policy);

			JsonNode configuration = TomlMapper.builder().build().readTree(Files.readString(destination));
			JsonNode filesystem = configuration.path("permissions").path("cat-runner").path("filesystem");
			requireThat(filesystem.path(keptCache.toString()).asString(), "keptCache").isEqualTo("write");
			requireThat(filesystem.path(protectedFolder.toString()).asString(), "protectedFolder").isEqualTo("read");
			requireThat(filesystem.path(secret.toString()).asString(), "secret").isEqualTo("deny");
		}
	}

	/**
	 * Verifies that the runner profile denies writes only to the protected names that exist in a dependency cache.
	 * Codex's Linux sandbox creates an empty placeholder at each denied path that is missing, so a denial of a missing
	 * name would add entries to the user's own cache folder.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void cacheDenialsNameOnlyExistingPaths() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-cache-denials-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path emptyCache = Files.createDirectories(root.resolve("npm-cache"));
			Path gitCache = Files.createDirectories(root.resolve("m2-cache"));
			Path existingGit = Files.createDirectories(gitCache.resolve(".git"));
			List<String> names = List.of(".git", ".codex", ".agents", ".aws");
			List<Path> denials = new ArrayList<>();
			for (Path cache : List.of(emptyCache, gitCache))
			{
				for (String name : names)
					denials.add(cache.resolve(name));
			}
			HarnessDependencyCaches caches = new HarnessDependencyCaches(List.of(emptyCache, gitCache), Map.of());
			ParentPolicy parent = new ParentPolicy(true, List.of(emptyCache, gitCache, sessionFolder), denials, List.of(),
				List.of(), HostAccess.NONE, false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(sessionFolder, caches.directories(),
				List.of(), Set.of()));
			Path destination = sessionFolder.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, sessionFolder.resolve("project"),
				caches, sessionFolder.resolve("home/tmp"), 0, null, Platform.LINUX_X64, CodexParentMultiAgentState.NONE,
				policy);

			JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-runner").path("filesystem");
			requireThat(filesystem.path(emptyCache.toString()).asString(), "emptyCache").isEqualTo("write");
			for (String name : names)
			{
				requireThat(filesystem.has(emptyCache.resolve(name).toString()), "emptyCache/" + name).isFalse();
				if (!name.equals(".git"))
					requireThat(filesystem.has(gitCache.resolve(name).toString()), "gitCache/" + name).isFalse();
			}
			requireThat(filesystem.path(existingGit.toString()).asString(), "existingGit").isEqualTo("read");
			try (Stream<Path> entries = Files.list(emptyCache))
			{
				requireThat(entries.toList(), "emptyCacheEntries").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a session may write the extra directories that its launch asks for, and reach the extra hosts,
	 * when the policy that bounds it allows them.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void sessionWritesExtraPermissions() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-extra-permissions-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path registry = Files.createDirectories(root.resolve("registry cache"));
			ParentPolicy parent = new ParentPolicy(true, List.of(sessionFolder, registry), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of("api.example", "registry.example")), false, List.of());
			ExtraPermissions extra = new ExtraPermissions(List.of(registry), Set.of("registry.example"));
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(sessionFolder, List.of(), List.of(),
				Set.of("api.example")).with(extra));
			Path destination = sessionFolder.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, sessionFolder.resolve("project"),
				HarnessDependencyCaches.NONE, sessionFolder.resolve("home/tmp"), 0, null, Platform.LINUX_X64,
				CodexParentMultiAgentState.NONE, policy, extra);

			JsonNode configuration = TomlMapper.builder().build().readTree(Files.readString(destination));
			JsonNode permissions = configuration.path("permissions").path("cat-runner");
			requireThat(permissions.path("filesystem").path(registry.toString()).asString(), "registry").
				isEqualTo("write");
			requireThat(Files.readString(destination), "configuration").contains("registry.example");
		}
	}

	/**
	 * Verifies that a prepared isolated home that had no {@code hooks.json} holds one that defines no hooks, because
	 * the sandbox's read entry for a missing path puts an empty file there, which a configuration reader rejects, and
	 * that the read entry stays so that a command cannot replace the file. A {@code hooks.json} already in the home is
	 * left as it is.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void isolatedHomeHoldsHooksFileWithoutHooks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-empty-hooks-")).toRealPath();
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path home = sessionFolder.resolve("home");
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(sessionFolder, List.of(),
				List.of(), Set.of()));
			Path destination = home.resolve("config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, sessionFolder.resolve("project"),
				HarnessDependencyCaches.NONE, home.resolve("tmp"), 0, null, Platform.LINUX_X64,
				CodexParentMultiAgentState.NONE, policy);

			JsonNode hooks = JsonMapper.builder().build().readTree(Files.readString(home.resolve("hooks.json")));
			requireThat(hooks.path("hooks").isObject(), "hooksIsObject").isTrue();
			requireThat(hooks.path("hooks").size(), "hookCount").isEqualTo(0);
			JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-runner").path("filesystem");
			requireThat(filesystem.path(home.resolve("hooks.json").toString()).asString(), "hooksAccess").
				isEqualTo("read");

			String existing = "{\"hooks\": {\"Stop\": []}}";
			Files.writeString(home.resolve("hooks.json"), existing);
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, sessionFolder.resolve("project"),
				HarnessDependencyCaches.NONE, home.resolve("tmp"), 0, null, Platform.LINUX_X64,
				CodexParentMultiAgentState.NONE, policy);
			requireThat(Files.readString(home.resolve("hooks.json")), "existingHooks").isEqualTo(existing);
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
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path home = sessionFolder.resolve("home");
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(sessionFolder, List.of(),
				List.of(), Set.of()));
			Path destination = home.resolve("config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, sessionFolder.resolve("project"),
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
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path secret = Files.createDirectories(root.resolve("secret"));
			Path inner = Files.writeString(secret.resolve("hidden.txt"), "private");
			ParentPolicy parent = new ParentPolicy(true, List.of(sessionFolder), List.of(), List.of(inner, secret),
				List.of(), HostAccess.NONE, false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(sessionFolder, List.of(), List.of(),
				Set.of()));
			Path destination = sessionFolder.resolve("home/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, sessionFolder.resolve("project"),
				HarnessDependencyCaches.NONE, sessionFolder.resolve("home/tmp"), 0, null, Platform.LINUX_X64,
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
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path secret = Files.createDirectories(root.resolve("secret"));
			Path home = Files.createDirectories(secret.resolve("home"));
			Path login = Files.writeString(Files.createDirectories(home.resolve(".claude")).
				resolve(".credentials.json"), "{}");
			ParentPolicy parent = new ParentPolicy(true, List.of(sessionFolder), List.of(), List.of(secret, login),
				List.of(), HostAccess.NONE, false, List.of());
			ChildPolicy policy = ChildPolicy.intersect(parent, new ChildNeeds(sessionFolder, List.of(), List.of(),
				Set.of()));
			Path destination = home.resolve("config.toml");

			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, sessionFolder.resolve("project"),
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
	 * lets it write a folder between them, as the nested session's home lies inside the denied results folder.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void regrantedSessionDenialStays() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-regranted-denial-")).toRealPath();
			Path results = Files.createDirectories(root.resolve("results"));
			Path home = Files.createDirectories(results.resolve("session/home"));
			Path login = Files.writeString(Files.createDirectories(home.resolve(".claude")).
				resolve(".credentials.json"), "{}");
			Path trials = Files.createDirectories(results.resolve("trials"));
			Path destination = home.resolve("config.toml");
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), destination, results.resolve("session/project"));

			CodexConnectionConfiguration.addSessionProfile(destination, List.of(), List.of(results, trials, login));

			JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(destination)).
				path("permissions").path("cat-denied-reads").path("filesystem");
			requireThat(filesystem.path(results.toString()).asString(), "results").isEqualTo("deny");
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
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-widening-layer-"));
			Path system = root.resolve("config.toml");
			Path managedLayer = root.resolve("managed_config.toml");
			Path file = system;
			if (managed)
				file = managedLayer;
			Files.writeString(file, layer);
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(root.resolve("session"),
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
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
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
				""".formatted(toml(sessionFolder.resolve("home"))));
			Path managed = root.resolve("managed_config.toml");
			ChildPolicy policy = ChildPolicy.intersect(ParentPolicy.UNSANDBOXED, new ChildNeeds(sessionFolder, List.of(),
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
				enabled = false

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
				contains(System.lineSeparator() + "enabled = false" + System.lineSeparator()).
				doesNotContain("other:hooks/hooks.json").doesNotContain("enabled = true");
		}
	}

	/**
	 * Verifies that the legacy proposal inspection recognizes a configured declaration by plugin name.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void readsPluginApprovalDeclarations() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-hook-trust-"));
			Path source = root.resolve("host.toml");
			Files.writeString(source, """
				[hooks.state."release-docs@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:c29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"
				enabled = true

				[hooks.state."release-docs-extra@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:b29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"

				[hooks.state."cat@cat:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:d29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"
				""");

			requireThat(CodexConnectionConfiguration.pluginHookApprovals(source, "release-docs"), "declarations").
				isEqualTo(Map.of("hooks/hooks.json:session_start:0:0",
					"sha256:c29ae6b8ed88cfdd4efd276d17a56b6a2d042b642cb79d6559ee64f91203a3d3"));
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
