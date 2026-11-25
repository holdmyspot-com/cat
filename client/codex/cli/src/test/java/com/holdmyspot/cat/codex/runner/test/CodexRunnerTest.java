package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.installer.CodexIntegration;
import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.codex.optimizer.RolloutInterruptSignal;
import com.holdmyspot.cat.codex.runner.CodexAuthentication;
import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexParentMultiAgentState;
import com.holdmyspot.cat.codex.runner.CodexProcess;
import com.holdmyspot.cat.codex.runner.CodexRunner.SessionSandbox;
import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.codex.runner.CodexRunnerCli;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InstalledRuntime;
import com.holdmyspot.cat.common.runner.LocalMarketplace;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestBroker;
import com.holdmyspot.cat.common.runner.SetupCommands;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
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
 * Tests {@link CodexRunner}.
 */
public final class CodexRunnerTest
{
	/**
	 * Quotes the paths that the rules name.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Verifies CAT is installed from this nested session's marketplace into the fresh isolated home.
	 */
	@Test
	public void reinstallsCatFromTheSessionMarketplace()
	{
		requireThat(CodexRunner.pluginSetupCommands("codex", Path.of("marketplace")), "commands").isEqualTo(List.of(
			List.of("codex", "plugin", "marketplace", "add", "marketplace"),
			List.of("codex", "plugin", "add", "cat@cat")));
	}

	/**
	 * Verifies a trial plugin is installed from its own session-local marketplace, which bears its name.
	 */
	@Test
	public void installsTrialPluginByName()
	{
		// Requirement: R-W12
		requireThat(CodexRunner.pluginSetupCommands("codex", Path.of("marketplace"), "release-docs"), "commands").
			isEqualTo(List.of(
				List.of("codex", "plugin", "marketplace", "add", "marketplace"),
				List.of("codex", "plugin", "add", "release-docs@release-docs")));
	}

	/**
	 * Verifies that a trial installs exactly its listed plugin, from a marketplace of its own, and neither CAT nor any
	 * other plugin.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsExactlyListedPlugin() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugins-"));
			Path home = root.resolve("isolated");
			List<String> steps = new ArrayList<>();
			List<List<String>> commands = new ArrayList<>();

			CodexRunner.installPlugins("codex", artifact(root), false, List.of(plugin(root, "release-docs")),
				root.resolve("host"), home, Map.of(), Yielding.executableFiles(), Yielding.fileReplacer(),
				(command, step) ->
				{
					commands.add(command);
					steps.add(step);
				});

			Path marketplace = home.resolve("plugins/release-docs-marketplace");
			requireThat(steps, "steps").isEqualTo(List.of("trial-plugin-release-docs-marketplace-add",
				"trial-plugin-release-docs-add"));
			requireThat(commands, "commands").isEqualTo(CodexRunner.pluginSetupCommands("codex", marketplace,
				"release-docs"));
			requireThat(Files.readString(marketplace.resolve("plugins/release-docs/README.md")), "copy").
				isEqualTo("release-docs");
			requireThat(Files.notExists(home.resolve("agents")), "catAgents").isTrue();
			requireThat(Files.notExists(home.resolve("rules")), "rules").isTrue();
		}
	}

	/**
	 * Verifies that a trial with two plugins installs both, in their listed order, each from its own marketplace.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsTwoPlugins() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugins-"));
			Path home = root.resolve("isolated");
			List<String> steps = new ArrayList<>();
			List<List<String>> commands = new ArrayList<>();

			CodexRunner.installPlugins("codex", artifact(root), false, List.of(plugin(root, "release-docs"),
				plugin(root, "tools")), root.resolve("host"), home, Map.of(), Yielding.executableFiles(),
				Yielding.fileReplacer(), (command, step) ->
				{
					steps.add(step);
					commands.add(List.copyOf(command));
				});

			requireThat(steps, "steps").isEqualTo(List.of("trial-plugin-release-docs-marketplace-add",
				"trial-plugin-release-docs-add", "trial-plugin-tools-marketplace-add", "trial-plugin-tools-add"));
			requireThat(commands, "commands").isEqualTo(List.of(
				List.of("codex", "plugin", "marketplace", "add", home.resolve("plugins/release-docs-marketplace").toString()),
				List.of("codex", "plugin", "add", "release-docs@release-docs"),
				List.of("codex", "plugin", "marketplace", "add", home.resolve("plugins/tools-marketplace").toString()),
				List.of("codex", "plugin", "add", "tools@tools")));
			requireThat(Files.readString(home.resolve("plugins/tools-marketplace/plugins/tools/README.md")), "copy").
				isEqualTo("tools");
		}
	}

	/**
	 * Verifies that CAT is installed only when the trial lists it: a trial that lists no plugin installs nothing, and
	 * one that lists CAT installs CAT and its agents from the artifact.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsCatOnlyWhenListed() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugins-"));
			Path artifact = artifact(root);
			List<String> steps = new ArrayList<>();

			Path withoutCat = root.resolve("without");
			CodexRunner.installPlugins("codex", artifact, false, List.of(), root.resolve("host"), withoutCat, Map.of(),
				Yielding.executableFiles(), Yielding.fileReplacer(), (_, step) -> steps.add(step));
			requireThat(steps, "withoutCatSteps").isEmpty();
			requireThat(Files.notExists(withoutCat), "withoutCatHome").isTrue();

			Path withCat = root.resolve("with");
			SetupCommands codex = codexAdds(withCat, "1.0+b0123456789ab");
			CodexRunner.installPlugins("codex", artifact, true, List.of(), root.resolve("host"), withCat,
				installationEnvironment(root),
				Yielding.executableFiles(), Yielding.fileReplacer(), (command, step) ->
				{
					steps.add(step);
					codex.execute(command, step);
				});
			requireThat(steps, "withCatSteps").isEqualTo(List.of("marketplace-add", "plugin-add"));
			requireThat(withCat.resolve("agents/cat-worker.toml"), "agent").isRegularFile();
		}
	}

	/**
	 * Verifies that CAT's marketplace is written where Codex's layout puts it for the Codex home that {@code CODEX_HOME}
	 * names: the plugin in the marketplace's plugin folder and the manifest that Codex reads to find it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void writesMarketplaceUnderCodexHome() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			Path home = root.resolve("custom-codex-home");
			CodexInstallation installation = CodexInstallation.fromEnvironment(Map.of("CODEX_HOME", home.toString()),
				root.resolve("user"));

			CodexRunner.installPlugins("codex", artifact(root), true, List.of(), root.resolve("host"), home,
				installationEnvironment(root),
				Yielding.executableFiles(), Yielding.fileReplacer(), codexAdds(home, "1.0+b0123456789ab"));

			requireThat(installation.marketplacePlugin().resolve("README.md"), "plugin").isRegularFile();
			requireThat(installation.marketplaceManifest(), "manifest").isRegularFile();
			requireThat(installation.marketplaceRoot().startsWith(home), "underCodexHome").isTrue();
			String source = JsonMapper.builder().build().readTree(Files.readString(installation.marketplaceManifest())).
				path("plugins").path(0).path("source").path("path").asString();
			requireThat(installation.marketplaceRoot().resolve(source).normalize(), "manifestSource").
				isEqualTo(installation.marketplacePlugin());
		}
	}

	/**
	 * Verifies that the isolated installation can still start its original Java after the owner removes its runtime.
	 *
	 * @throws IOException if the fixture cannot be written or the isolated plugin's runtime cannot be used
	 */
	@Test
	public void retainsJavaAfterOwnerRuntimeIsRemoved() throws IOException
	{
		// Requirement: R-I12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-retained-runtime-"));
			Platform platform = Platform.current();
			Path ownerHome = root.resolve("owner");
			Path artifact = artifact(ownerHome);
			Path ownerRuntime = FakeRelease.runtime(artifact.resolve(PluginDirectory.RUNTIME), platform);
			Files.createFile(artifact.resolve(RuntimeBinding.BUNDLED_PATH));
			RuntimeBinding.write(artifact, ownerRuntime);
			String originalIdentity = InstalledRuntime.identity(ownerRuntime);
			Path home = root.resolve("isolated");
			CodexRunner.installPlugins("codex", artifact, true, List.of(), ownerHome, home, installationEnvironment(root),
				Yielding.executableFiles(), Yielding.fileReplacer(), codexAdds(home, "1.0+b0123456789ab"));

			Files.move(ownerRuntime, root.resolve("removed-runtime"));

			Path installed = CodexRunner.installedCat(home);
			requireThat(Files.readString(installed.resolve(RuntimeBinding.PATH)), "retainedBinding").
				isEqualTo(ownerRuntime + "\n");
			Path runtime = RuntimeBinding.readUsable(installed, platform);
			requireThat(runtime, "runtime").isEqualTo(installed.resolve(PluginDirectory.RUNTIME));
			requireThat(InstalledRuntime.identity(runtime), "runtimeIdentity").isEqualTo(originalIdentity);
		}
	}

	/**
	 * Verifies path-only plugin installation cannot inherit approvals by manifest name.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void withholdsUnboundPluginApprovals() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-plugins-"));
			Path host = Files.createDirectories(root.resolve("host"));
			Files.writeString(host.resolve("config.toml"), """
				[hooks.state."release-docs@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%1$s"

				[hooks.state."tools@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%2$s"

				[hooks.state."other@acme:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%3$s"

				[hooks.state."cat@cat:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%4$s"
				""".formatted("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64)));
			Path home = root.resolve("isolated");

			CodexRunner.installPlugins("codex", artifact(root), true, List.of(plugin(root, "release-docs"),
				plugin(root, "tools")), host, home, installationEnvironment(root), Yielding.executableFiles(),
				Yielding.fileReplacer(),
				codexAdds(home, "1.0+b0123456789ab"));

			String configuration = Files.readString(home.resolve("config.toml"));
			requireThat(configuration, "configuration").
				doesNotContain("release-docs@release-docs").doesNotContain("tools@tools").
				contains(approval("cat@cat", "d")).
				doesNotContain("other@").doesNotContain("c".repeat(64));
		}
	}

	/**
	 * Verifies that installing CAT writes into the isolated home the entries that CAT's installer writes for the
	 * installed version: the execution-policy rules for its installer and request server, and the hash of each of its
	 * hooks, which replaces a copied approval of another hash. The runner's own configuration stays.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void installsCatRulesAndHookHashes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-rules-"));
			Path host = Files.createDirectories(root.resolve("host"));
			Files.writeString(host.resolve("config.toml"), """
				[hooks.state."cat@cat:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%s"
				""".formatted("d".repeat(64)));
			Path home = Files.createDirectories(root.resolve("isolated"));
			Path artifact = artifact(root);
			Files.createDirectories(artifact.resolve("hooks"));
			Files.writeString(artifact.resolve("hooks/hooks.json"), """
				{"hooks": {"SessionStart": [{"hooks": [{"type": "command", "command": "run-session-start"}]}]}}
				""");
			CodexConnectionConfiguration.copy(host.resolve("config.toml"), home.resolve("config.toml"),
				root.resolve("project"), HarnessDependencyCaches.NONE, home.resolve("tmp"), 0, Platform.current());

			Map<String, String> environment = installationEnvironment(root);
			CodexRunner.installPlugins("codex", artifact, true, List.of(), host, home, environment,
				Yielding.executableFiles(), Yielding.fileReplacer(), codexAdds(home, "1.0+b0123456789ab"));

			Path installer = home.resolve("plugins/cache/cat/cat/1.0+b0123456789ab").
				resolve(PluginDirectory.INSTALLER);
			Map<String, List<String>> entries = new CodexIntegration().permissions(environment, installer);
			String rules = Files.readString(home.resolve("rules/cat-installer.rules"));
			requireThat(rules, "rules").isEqualTo(entries.get("rules/cat-installer.rules#").get(0));
			if (!Platform.current().isWindows())
			{
				requireThat(rules, "rules").contains("prefix_rule(pattern=[" +
					JSON.writeValueAsString(installer.resolveSibling("request-server").toString()) +
					"], decision=\"allow\")");
			}
			String hash = null;
			for (Map.Entry<String, List<String>> entry : entries.entrySet())
			{
				if (entry.getKey().startsWith("config.toml#hooks.state."))
					hash = entry.getValue().get(0);
			}
			requireThat(hash, "hash").isNotNull();
			JsonNode configuration = TomlMapper.builder().build().readTree(Files.readString(home.resolve("config.toml")));
			requireThat(configuration.path("hooks").path("state").path("cat@cat:hooks/hooks.json:session_start:0:0").
				path("trusted_hash").asString(), "trustedHash").isEqualTo(hash);
			requireThat(configuration.path("default_permissions").asString(), "defaultPermissions").isNotEmpty();
		}
	}

	/**
	 * Verifies that installing CAT fails, and writes no rules, unless Codex's cache holds exactly one copy of it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void requiresOneCachedCatCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-rules-"));
			for (String[] versions : new String[][]
				{
					{},
					{"1.0+b0123456789ab", "1.1+b0123456789ab"}
				})
			{
				Path home = root.resolve("isolated" + versions.length);
				IOException e = expectThrows(IOException.class, () -> CodexRunner.installPlugins("codex", artifact(root),
					true, List.of(), root.resolve("host"), home, Map.of(), Yielding.executableFiles(),
					Yielding.fileReplacer(), codexAdds(home, versions)));

				requireThat(e.getMessage(), "message").contains("Codex did not install exactly one copy of cat@cat");
				requireThat(Files.notExists(home.resolve("rules")), "rules").isTrue();
			}
		}
	}

	/**
	 * Returns test-owned installer bindings, including the Windows system and executable paths.
	 *
	 * @param root the fixture root containing the bound paths
	 * @return the installer environment
	 */
	private static Map<String, String> installationEnvironment(Path root)
	{
		return Map.of("SystemRoot", root.resolve("bound-system").toString(),
			"PATH", root.resolve("bound-tools").toString(), "PATHEXT", ".EXE;.CMD");
	}

	/**
	 * Returns the setup commands of a Codex that installs CAT: {@code plugin add} copies CAT from its marketplace into
	 * the cache folder of each version, and records CAT as installed in {@code config.toml}.
	 *
	 * @param home the isolated Codex home
	 * @param versions the names of the version folders that Codex's cache ends up holding
	 * @return the setup commands, which change nothing for any other step
	 */
	private static SetupCommands codexAdds(Path home, String... versions)
	{
		return (_, step) ->
		{
			if (!step.equals("plugin-add"))
				return;
			for (String version : versions)
				LocalMarketplace.copy(home.resolve("plugins/cat-marketplace/plugins/cat"),
					home.resolve("plugins/cache/cat/cat").resolve(version));
			Files.writeString(home.resolve("config.toml"), System.lineSeparator() + "[plugins.\"cat@cat\"]" +
				System.lineSeparator() + "enabled = true" + System.lineSeparator(), StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
		};
	}

	/**
	 * Returns an approval of a plugin's session-start hook as the isolated configuration holds it.
	 *
	 * @param plugin the plugin and marketplace, {@code <plugin>@<marketplace>}
	 * @param digit the hexadecimal digit that the approved hash repeats
	 * @return the approval's table
	 */
	private static String approval(String plugin, String digit)
	{
		return "[hooks.state.\"" + plugin + ":hooks/hooks.json:session_start:0:0\"]" + System.lineSeparator() +
			"trusted_hash = \"sha256:" + digit.repeat(64) + "\"";
	}

	/**
	 * Creates a CAT artifact that holds the Codex manifest and one custom agent.
	 *
	 * @param root the directory to create the artifact in
	 * @return the artifact directory
	 * @throws IOException if the artifact cannot be written
	 */
	private static Path artifact(Path root) throws IOException
	{
		Path artifact = plugin(root, "cat");
		Files.createDirectories(artifact.resolve("agents/codex"));
		Files.writeString(artifact.resolve("agents/codex/worker.toml"), "name = \"worker\"\n");
		return artifact;
	}

	/**
	 * Creates a Codex plugin directory, named after the plugin, that holds a manifest and a readme.
	 *
	 * @param root the directory to create the plugin in
	 * @param name the manifest's plugin name, which the readme also holds
	 * @return the plugin directory
	 * @throws IOException if the plugin cannot be written
	 */
	private static Path plugin(Path root, String name) throws IOException
	{
		Path plugin = root.resolve("sources").resolve(name);
		Files.createDirectories(plugin.resolve(".codex-plugin"));
		Files.writeString(plugin.resolve(".codex-plugin/plugin.json"), "{\"name\": \"" + name + "\"}\n");
		Files.writeString(plugin.resolve("README.md"), name);
		return plugin;
	}

	/**
	 * Verifies the app-server runs CAT's hooks when CAT is installed, and loads no plugin when it is not.
	 */
	@Test
	public void startsAppServerForInstallation()
	{
		requireThat(CodexRunner.appServerCommand("codex", true), "withCat").isEqualTo(
			List.of("codex", "app-server", "--stdio", "--enable", "hooks"));
		requireThat(CodexRunner.appServerCommand("codex", false), "withoutCat").isEqualTo(
			List.of("codex", "app-server", "--stdio", "--disable", "plugins"));
	}

	/**
	 * Verifies that on Linux a trial's app-server starts with the {@code TMPDIR} that the trial's configuration sets
	 * for its shell commands, rather than the caller's, so that the filesystem helpers that the app-server starts and
	 * the shell commands' sandboxes share Codex's record of the directories that they mount over.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void appServerSharesShellTemporaryDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-tmpdir-"));
			Path sessionFolder = Files.createDirectories(root.resolve("session"));
			Path home = Files.createDirectories(sessionFolder.resolve("home"));
			Path temporaryDirectory = Files.createDirectories(home.resolve("tmp"));
			Path configuration = home.resolve(".codex/config.toml");
			CodexConnectionConfiguration.copy(root.resolve("host.toml"), configuration, sessionFolder.resolve("project"),
				HarnessDependencyCaches.NONE, temporaryDirectory, 0, Platform.LINUX_X64);
			String shellTemporaryDirectory = TomlMapper.builder().build().readTree(Files.readString(configuration)).
				path("shell_environment_policy").path("set").path("TMPDIR").asString();

			Map<String, String> appServer = CodexRunner.processEnvironment(Map.of("PATH", "/usr/bin",
				"TMPDIR", root.resolve("host-tmp").toString()), HarnessDependencyCaches.NONE, home, sessionFolder,
				temporaryDirectory, Platform.LINUX_X64, apiKeySource(root, "source"));

			requireThat(shellTemporaryDirectory, "shellTemporaryDirectory").isEqualTo(temporaryDirectory.toString());
			requireThat(appServer.get("TMPDIR"), "appServerTemporaryDirectory").isEqualTo(shellTemporaryDirectory);
		}
	}

	/**
	 * Verifies that a Codex child needs the host of its source configuration's base URL, the shared caches, and the
	 * source files that the runner reads for its credential, whatever the caller's own Codex folder names.
	 *
	 * @throws IOException if the fixture or the report cannot be built
	 */
	@Test
	public void childNeedsSourceHostCachesAndFiles() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-child-needs-")).toRealPath();
			Path codexHome = Files.createDirectory(home.resolve(".codex"));
			Files.writeString(codexHome.resolve("config.toml"), "openai_base_url = \"https://runner.example/v1\"\n");
			Path source = CodexSourceHomes.create(home, "source");
			Path configuration = CodexSourceHomes.configuration(source.resolve(".codex"),
				"openai_base_url = \"https://Gateway.Example/v1\"\n");
			Path login = CodexSourceHomes.apiKeyLogin(source.resolve(".codex"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), home.toString(),
				"CODEX_HOME", codexHome.toString());
			CodexAuthentication authentication = CodexAuthentication.read(new AuthenticationOptions(
				Optional.of(source), Optional.of(HostLogin.API_KEY), false), environment, Platform.current());

			ChildNeedsReport needs = CodexRunner.childNeeds(environment, authentication);

			requireThat(needs.hosts(), "hosts").isEqualTo(Set.of("gateway.example"));
			requireThat(needs.writes(), "writes").isEqualTo(List.of(home.resolve(".npm"), home.resolve(".m2")));
			requireThat(needs.logins(), "logins").isEqualTo(List.of(configuration, login));
		}
	}

	/**
	 * Verifies that the app-server's environment marks the session as a nested session, so that the session's commands
	 * send their sessions to the nested session's runner.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void marksNestedSession() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-marker-")).toRealPath();
			Path home = root.resolve("session/home");
			Map<String, String> appServer = CodexRunner.processEnvironment(Map.of("PATH", "/usr/bin",
				HarnessEnvironment.NESTED_SESSION_VARIABLE, ""), HarnessDependencyCaches.NONE, home, home.getParent(),
				home.resolve("tmp"), Platform.LINUX_X64, apiKeySource(root, "source"));

			requireThat(appServer.get(HarnessEnvironment.NESTED_SESSION_VARIABLE), "marker").isEqualTo("1");
		}
	}

	/**
	 * Verifies that the app-server receives neither of the runner's {@code CODEX_API_KEY} and {@code OPENAI_API_KEY},
	 * nor any fixture secret as a variable's value, and that it states the class of the source's login in
	 * {@code CAT_HOST_LOGIN} and names the session home's {@code .codex} folder as its {@code CODEX_HOME}.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void appServerGetsNoRunnerKeys() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-keys-")).toRealPath();
			Path home = root.resolve("session/home");

			Map<String, String> appServer = CodexRunner.processEnvironment(Map.of("PATH", "/usr/bin", "CODEX_API_KEY",
				CodexSourceHomes.SECRET, "OPENAI_API_KEY", CodexSourceHomes.SECRET), HarnessDependencyCaches.NONE, home,
				home.getParent(), home.resolve("tmp"), Platform.LINUX_X64, apiKeySource(root, "source"));

			requireThat(appServer.keySet(), "variables").doesNotContain("CODEX_API_KEY").doesNotContain("OPENAI_API_KEY");
			requireThat(appServer.values(), "values").doesNotContain(CodexSourceHomes.SECRET);
			requireThat(appServer.get(HarnessEnvironment.HOST_LOGIN_VARIABLE), "hostLogin").isEqualTo("api_key");
			requireThat(appServer.get("CODEX_HOME"), "codexHome").isEqualTo(home.resolve(".codex").toString());
		}
	}

	/**
	 * Verifies that the session's commands get the session home's {@code .codex} folder as their {@code CODEX_HOME},
	 * and the session home as their home folder, and that they receive neither {@code CODEX_API_KEY} nor
	 * {@code OPENAI_API_KEY}.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void commandsUseCodexFolder() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-folder-")).toRealPath();
			Path home = Files.createDirectories(root.resolve("session/home"));
			Path temporaryDirectory = Files.createDirectories(home.resolve("tmp"));
			Path configuration = home.resolve(".codex/config.toml");

			CodexConnectionConfiguration.copy(root.resolve("host.toml"), configuration, home,
				apiKeySource(root, "source").settings(), root.resolve("session/project"), HarnessDependencyCaches.NONE,
				temporaryDirectory, 0, null, Platform.LINUX_X64, CodexParentMultiAgentState.NONE,
				ChildPolicy.intersect(ParentPolicy.UNSANDBOXED,
					new ChildNeeds(root, List.of(), List.of(), Set.of())), ExtraPermissions.NONE, null);

			JsonNode policy = TomlMapper.builder().build().readTree(Files.readString(configuration)).
				path("shell_environment_policy");
			requireThat(policy.path("set").path("CODEX_HOME").asString(), "codexHome").
				isEqualTo(home.resolve(".codex").toString());
			requireThat(policy.path("set").path("HOME").asString(), "home").isEqualTo(home.toString());
			List<String> excluded = new ArrayList<>();
			for (JsonNode variable : policy.path("exclude"))
				excluded.add(variable.asString());
			requireThat(excluded, "excluded").contains("CODEX_API_KEY").contains("OPENAI_API_KEY");
		}
	}

	/**
	 * Verifies that for a source with an API-key {@code auth.json} the session's {@code .codex} folder receives a copy
	 * equal to it and readable by its owner alone, and that for a source without one it receives none while the
	 * runner's own Codex folder holds one.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void copiesSourceLoginOwnerOnly() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-copy-")).toRealPath();
			Path codexFolder = Files.createDirectories(root.resolve("session/home/.codex"));
			Path login = CodexSourceHomes.apiKeyLogin(CodexSourceHomes.create(root, "source").resolve(".codex"));

			apiKeySource(root, "source").copyLogin(codexFolder);

			Path copy = codexFolder.resolve("auth.json");
			requireThat(Files.readString(copy), "copy").isEqualTo(Files.readString(login));
			if (Files.getFileStore(copy).supportsFileAttributeView("posix"))
			{
				requireThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(copy)), "permissions").
					isEqualTo("rw-------");
			}

			Path other = Files.createDirectories(root.resolve("other/home/.codex"));
			Map<String, String> environment = CodexSourceHomes.runnerEnvironment(root);
			tokenCommandSource(root, "command-source").copyLogin(other);
			requireThat(Files.exists(Path.of(environment.get(Platform.current().homeVariable()), ".codex/auth.json")),
				"runnerLogin").isTrue();
			requireThat(Files.exists(other.resolve("auth.json")), "otherCopy").isFalse();
		}
	}

	/**
	 * Verifies that the session's login copy exists while its app-server runs and is gone once the session is closed.
	 * The stand-in for the app-server is {@code cat}, which exits when its input closes.
	 *
	 * @throws IOException if the fixture or the process fails
	 */
	@Test(timeOut = 10_000)
	// The test closes the session itself, because the close is the step whose effect it checks
	@SuppressWarnings("PMD.CloseResource")
	public void removesLoginCopyWhenAppServerEnds() throws IOException
	{
		// Requirement: R-I15
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-removal-")).toRealPath();
			Path codexFolder = Files.createDirectories(root.resolve("session/home/.codex"));
			apiKeySource(root, "source").copyLogin(codexFolder);
			Path copy = codexFolder.resolve("auth.json");
			Process appServer = new ProcessBuilder("cat").start();
			HarnessProcess session = CodexRunner.removingLoginCopy(new CodexProcess(appServer, scope, false, null),
				codexFolder);
			requireThat(Files.exists(copy), "whileRunning").isTrue();

			session.close();

			requireThat(appServer.isAlive(), "appServerAlive").isFalse();
			requireThat(Files.exists(copy, LinkOption.NOFOLLOW_LINKS), "copyExists").isFalse();
		}
	}

	/**
	 * Verifies that a resume copies the resuming launch's source login over the copy that an earlier launch left, and
	 * that a resume whose source holds no {@code auth.json} removes such a copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void resumeCopiesResumingSourceLogin() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-resume-")).toRealPath();
			Path codexFolder = Files.createDirectories(root.resolve("session/home/.codex"));
			apiKeySource(root, "first").copyLogin(codexFolder);
			Path second = CodexSourceHomes.create(root, "second");
			Path secondLogin = CodexSourceHomes.ownerOnly(Files.writeString(second.resolve(".codex/auth.json"), """
				{"auth_mode": "apikey", "OPENAI_API_KEY": "%s-second"}
				""".formatted(CodexSourceHomes.SECRET)));

			CodexAuthentication.read(named(second, null), Map.of(), Platform.current()).copyLogin(codexFolder);
			requireThat(Files.readString(codexFolder.resolve("auth.json")), "resumed").
				isEqualTo(Files.readString(secondLogin));

			tokenCommandSource(root, "third").copyLogin(codexFolder);
			requireThat(Files.exists(codexFolder.resolve("auth.json")), "removed").isFalse();
		}
	}

	/**
	 * Verifies that a resume of a session whose Codex files are in the earlier layout, directly in its home, moves them
	 * into its {@code .codex} folder first, so that its thread's rollout, its configuration and the paths that the
	 * configuration names are found there, while the home's temporary directory stays; and that a session already in
	 * the new layout is left as it is.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void movesEarlierLayoutIntoCodexFolder() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-layout-")).toRealPath();
			Path home = Files.createDirectories(root.resolve("session/home"));
			Files.createDirectories(home.resolve("tmp"));
			Path marketplace = Files.createDirectories(home.resolve("marketplaces/cat"));
			Files.writeString(home.resolve("config.toml"), """
				model = "gpt-6-luna"
				[marketplaces.cat]
				source = "%s"
				""".formatted(marketplace));
			String thread = "019a0000-0000-7000-8000-000000000001";
			Path rollouts = Files.createDirectories(home.resolve("sessions/2026/11/25"));
			Files.writeString(rollouts.resolve("rollout-2026-11-25T00-00-00-" + thread + ".jsonl"), "{}\n");

			CodexRunner.moveEarlierLayout(home);

			Path codexFolder = home.resolve(".codex");
			requireThat(RolloutInterruptSignal.locate(codexFolder, thread).isPresent(), "rolloutFound").isTrue();
			JsonNode configuration = TomlMapper.builder().build().readTree(Files.readString(
				codexFolder.resolve("config.toml")));
			requireThat(configuration.path("model").asString(), "model").isEqualTo("gpt-6-luna");
			requireThat(configuration.path("marketplaces").path("cat").path("source").asString(), "marketplace").
				isEqualTo(codexFolder.resolve("marketplaces/cat").toString());
			requireThat(Files.isDirectory(codexFolder.resolve("marketplaces/cat")), "marketplaceMoved").isTrue();
			requireThat(Files.exists(home.resolve("config.toml")), "earlierConfiguration").isFalse();
			requireThat(Files.isDirectory(home.resolve("tmp")), "temporaryDirectoryKept").isTrue();

			Files.writeString(home.resolve("config.toml"), "model = \"later\"\n");
			CodexRunner.moveEarlierLayout(home);
			requireThat(Files.exists(home.resolve("config.toml")), "newLayoutKept").isTrue();
		}
	}

	/**
	 * Verifies that the evidence of a source home names the home, the settings and files that the session takes and
	 * the class, that the nested session's {@code session.json} records it, and that neither it, the session's
	 * settings nor the authentication's description holds a fixture secret.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void recordsSourceEvidenceWithoutSecrets() throws IOException
	{
		// Requirement: R-I15
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-codex-evidence-")).
				toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.apiKeyLogin(source.resolve(".codex"));
			CodexSourceHomes.secret(source.resolve("key"));
			CodexSourceHomes.configuration(source.resolve(".codex"), """
				model_provider = "gateway"
				openai_base_url = "https://openai-proxy.example/v1"
				forced_login_method = "api"
				[model_providers.gateway]
				base_url = "https://gateway.example/v1"
				[model_providers.gateway.auth]
				command = "sh"
				args = ["-c", "cat ~/key"]
				""");
			CodexAuthentication authentication = CodexAuthentication.read(named(source, HostLogin.API_KEY), Map.of(),
				Platform.current());
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");

			manager.initialize(nestedSession, root.resolve("artifact"), root.resolve("project"), "codex_app_server",
				List.of("codex", "app-server"), authentication.evidence());

			requireThat(authentication.evidence().home(), "home").isEqualTo(source);
			requireThat(authentication.evidence().parts(), "parts").isEqualTo(List.of("model_provider",
				"openai_base_url", "forced_login_method", "model_providers.gateway", ".codex/auth.json"));
			requireThat(authentication.evidence().loginClass(), "loginClass").isEqualTo(HostLogin.API_KEY);
			String recorded = Files.readString(nestedSession.directory().resolve("session.json"));
			requireThat(recorded, "recorded").contains("model_providers.gateway").
				doesNotContain(CodexSourceHomes.SECRET);
			requireThat(authentication.settings(), "settings").doesNotContain(CodexSourceHomes.SECRET);
			requireThat(authentication.toString(), "description").doesNotContain(CodexSourceHomes.SECRET);
		}
	}

	/**
	 * Verifies that a nested session's transcripts are the rollouts that its Codex writes under its home's
	 * {@code .codex} folder, which the prompt optimizer reads from the nested session's session transcripts.
	 *
	 * @throws IOException if the fixture cannot be written or the rollouts cannot be copied
	 */
	@Test
	public void copiesRolloutsFromCodexFolder() throws IOException
	{
		// Requirement: R-I15
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-codex-rollouts-")).
				toRealPath();
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path rollouts = Files.createDirectories(nestedSession.directory().resolve("home/.codex/sessions/2026/11/25"));
			Files.writeString(rollouts.resolve("rollout-thread.jsonl"), "{}\n");

			CodexRunner.sessionTranscripts(nestedSession).copy();

			List<String> copied = new ArrayList<>();
			try (Stream<Path> files = Files.walk(nestedSession.sessionTranscripts()))
			{
				files.filter(Files::isRegularFile).forEach(file -> copied.add(file.getFileName().toString()));
			}
			requireThat(copied, "copied").isEqualTo(List.of("rollout-thread.jsonl"));
		}
	}

	/**
	 * Verifies that the source's {@code auth.json} and its copy, and a file that the provider's token command names,
	 * are in the session's denied reads and its permission profile's denied paths, and in the denied reads of every
	 * session that it requests, while the session's own {@code config.toml} stays readable.
	 *
	 * @throws IOException if the fixture or the configuration cannot be built
	 */
	@Test
	public void deniesSourceSecretsAndLoginCopy() throws IOException
	{
		// Requirement: R-I15
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-codex-denials-")).
				toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			Path login = CodexSourceHomes.apiKeyLogin(source.resolve(".codex"));
			Path key = CodexSourceHomes.secret(source.resolve("key"));
			CodexSourceHomes.configuration(source.resolve(".codex"), """
				model_provider = "gateway"
				[model_providers.gateway]
				base_url = "https://gateway.example/v1"
				[model_providers.gateway.auth]
				command = "sh"
				args = ["-c", "cat ~/key"]
				""");
			Map<String, String> environment = CodexSourceHomes.runnerEnvironment(root);
			CodexAuthentication authentication = CodexAuthentication.read(named(source, HostLogin.API_KEY), environment,
				Platform.current());
			HarnessRunnerOptions options = authentication.withDeniedReads(CodexRunnerCli.options(new String[]{"serve",
				"--artifact", root.resolve("artifact").toString(), "--results", root.resolve("results").toString(),
				"--model", "gpt-6-luna", "--effort", "low"}, HarnessTemporaryRoot.withoutSession("no session")));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root.resolve("results"),
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path codexFolder = Files.createDirectories(nestedSession.directory().resolve("home/.codex"));
			authentication.copyLogin(codexFolder);
			Path configuration = codexFolder.resolve("config.toml");
			CodexConnectionConfiguration.copy(root.resolve("missing.toml"), configuration,
				nestedSession.directory().resolve("project"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(),
				options.deniedReads(), Platform.LINUX_X64, environment);
			CodexConnectionConfiguration.addSessionProfile(configuration, List.of(), deniedReads);

			List<Path> secrets = List.of(login, key);
			requireThat(options.deniedReads(), "requestedSessionDenials").containsAll(secrets);
			requireThat(deniedReads, "deniedReads").containsAll(secrets).contains(codexFolder.resolve("auth.json")).
				doesNotContain(configuration);
			String profile = Files.readString(configuration);
			for (Path denied : List.of(login, key, codexFolder.resolve("auth.json")))
				requireThat(profile, "profile").contains("\"" + denied + "\" = \"deny\"");
		}
	}

	/**
	 * Verifies bubblewrap is the first executable {@code bwrap} file on the caller's {@code PATH}, skipping a directory
	 * of that name and, where the file system has permission bits, a file of that name that is not executable, and
	 * that it is absent when no directory on the {@code PATH} has one.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsBubblewrapOnPath() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path empty = cleanup.add(Files.createTempDirectory("cat-path-"));
			Path notFile = cleanup.add(Files.createTempDirectory("cat-path-"));
			Files.createDirectory(notFile.resolve("bwrap"));
			Path notExecutable = cleanup.add(Files.createTempDirectory("cat-path-"));
			Path usable = cleanup.add(Files.createTempDirectory("cat-path-"));
			Path bwrap = Files.createFile(usable.resolve("bwrap"));
			if (PortableFiles.hasModes(usable))
			{
				Files.createFile(notExecutable.resolve("bwrap"), PosixFilePermissions.asFileAttribute(
					PosixFilePermissions.fromString("rw-r--r--")));
				Files.setPosixFilePermissions(bwrap, PosixFilePermissions.fromString("rwxr-xr-x"));
			}
			String path = String.join(File.pathSeparator, empty.toString(), notFile.toString(), notExecutable.toString(),
				usable.toString());

			requireThat(CodexRunner.bubblewrap(Map.of("PATH", path)), "found").isEqualTo(Optional.of(bwrap));
			requireThat(CodexRunner.bubblewrap(Map.of("PATH", empty.toString())), "absent").isEmpty();
			requireThat(CodexRunner.bubblewrap(Map.of()), "withoutPath").isEmpty();
		}
	}

	/**
	 * Verifies that the app-server's bubblewrap replaces each hidden directory with an empty, read-only one and mounts
	 * back inside it, after the paths that contain them, the paths that the session writes and then those that it
	 * only reads, so that a read-only path inside a writable one stays read-only. A path outside every hidden
	 * directory needs no mount.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void hidesDirectoriesAroundSessionPaths() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path hidden = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-hidden-")).toRealPath();
			Path project = Files.createDirectories(hidden.resolve("results/session/project"));
			Path home = Files.createDirectories(hidden.resolve("results/session/home"));
			Path responses = Files.createDirectories(home.resolve("responses"));
			Path evidence = Files.createDirectories(hidden.resolve("results/trial/artifacts"));
			Path outside = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-outside-")).toRealPath();
			Path bwrap = Path.of("/usr/bin/bwrap").toAbsolutePath();
			List<String> command = CodexRunner.appServerCommand("codex", false);

			requireThat(CodexRunner.withHiddenDirectories(command, bwrap, List.of(hidden), List.of(project, home),
				List.of(responses, evidence, outside)), "command").isEqualTo(List.of(
				bwrap.toString(), "--dev-bind", "/", "/",
				"--tmpfs", hidden.toString(),
				"--bind", home.toString(), home.toString(),
				"--bind", project.toString(), project.toString(),
				"--ro-bind", evidence.toString(), evidence.toString(),
				"--ro-bind", responses.toString(), responses.toString(),
				"--remount-ro", hidden.toString(),
				"--die-with-parent", "--", "codex", "app-server", "--stdio", "--disable", "plugins"));
		}
	}

	/**
	 * Verifies that a trial session, which reads no evidence, keeps the sandbox that it had before judges could read
	 * evidence, with bubblewrap on the {@code PATH}: its app-server runs without bubblewrap, which would hide nothing,
	 * and its permission profile denies the whole results folder and the paths that the caller denies.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void trialSandboxHidesNothingMore() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			manager.snapshotProject(nestedSession);
			manager.create("codex");
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));
			Path bin = cleanup.add(Files.createTempDirectory(temporary, "cat-bin-"));
			Path bwrap = Files.writeString(bin.resolve("bwrap"), "");
			requireThat(bwrap.toFile().setExecutable(true), "setExecutable").isTrue();
			Path configuration = nestedSession.directory().resolve("home/config.toml");
			CodexConnectionConfiguration.copy(nestedSession.directory().resolve("missing.toml"), configuration,
				nestedSession.directory().resolve("project"));
			List<String> appServer = CodexRunner.appServerCommand("codex", false);
			Map<String, String> environment = Map.of("PATH", bin.toString());

			SessionSandbox sandbox = CodexRunner.sessionSandbox(configuration, manager, nestedSession, List.of(),
				List.of(heldOut), Platform.LINUX_X64, environment, null, appServer);

			requireThat(sandbox.command(), "command").isEqualTo(appServer);
			JsonNode denied = TomlMapper.builder().build().readTree(Files.readString(configuration)).
				path("permissions").path(sandbox.profile()).path("filesystem");
			for (Path path : List.of(root, heldOut))
			{
				requireThat(denied.path(path.toRealPath().toString()).stringValue(""), "access(" + path + ")").
					isEqualTo("deny");
			}
		}
	}

	/**
	 * Verifies that on Linux a session that may read evidence inside a directory that it is denied, here the results
	 * folder, is refused when the caller's {@code PATH} has no bubblewrap, with a message that names the missing
	 * program, why the session needs it and how to install it, before the session's configuration receives a permission
	 * profile.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesEvidenceWithoutBubblewrapOnLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			manager.snapshotProject(nestedSession);
			NestedSession trial = manager.create("codex");
			Path evidence = Files.createDirectories(trial.directory().resolve("artifacts"));
			Path bin = cleanup.add(Files.createTempDirectory(temporary, "cat-bin-"));
			Path configuration = nestedSession.directory().resolve("home/config.toml");
			CodexConnectionConfiguration.copy(nestedSession.directory().resolve("missing.toml"), configuration,
				nestedSession.directory().resolve("project"));
			String before = Files.readString(configuration);

			List<String> appServer = CodexRunner.appServerCommand("codex", false);
			Map<String, String> environment = Map.of("PATH", bin.toString());

			try
			{
				CodexRunner.sessionSandbox(configuration, manager, nestedSession, List.of(evidence), List.of(),
					Platform.LINUX_X64, environment, null, appServer);
				throw new AssertionError("The session started without bubblewrap");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").
					startsWith("On Linux, a Codex session that reads evidence needs bubblewrap (bwrap)").
					contains(evidence.toString()).contains(root.toAbsolutePath().normalize().toString()).
					contains(bin.toString()).contains("bubblewrap package");
			}
			requireThat(Files.readString(configuration), "configuration").isEqualTo(before);
		}
	}

	/**
	 * Verifies that on Linux a session that reads no evidence, such as a trial, starts without bubblewrap on the
	 * caller's {@code PATH}: its app-server command is not wrapped and its permission profile denies the whole results
	 * folder and the paths that the caller denies.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsTrialWithoutBubblewrapOnLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			manager.snapshotProject(nestedSession);
			manager.create("codex");
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));
			Path bin = cleanup.add(Files.createTempDirectory(temporary, "cat-bin-"));
			Path configuration = nestedSession.directory().resolve("home/config.toml");
			CodexConnectionConfiguration.copy(nestedSession.directory().resolve("missing.toml"), configuration,
				nestedSession.directory().resolve("project"));
			List<String> appServer = CodexRunner.appServerCommand("codex", false);

			SessionSandbox sandbox = CodexRunner.sessionSandbox(configuration, manager, nestedSession, List.of(),
				List.of(heldOut), Platform.LINUX_X64, Map.of("PATH", bin.toString()), null, appServer);

			requireThat(sandbox.command(), "command").isEqualTo(appServer);
			JsonNode denied = TomlMapper.builder().build().readTree(Files.readString(configuration)).
				path("permissions").path(sandbox.profile()).path("filesystem");
			for (Path path : List.of(root, heldOut))
			{
				requireThat(denied.path(path.toRealPath().toString()).stringValue(""), "access(" + path + ")").
					isEqualTo("deny");
			}
		}
	}

	/**
	 * Verifies that on Linux a session whose project links to a file outside it reads the nested session's link-targets
	 * directory, which holds the read-only copy that the link leads to, without writing it: the permission profile grants
	 * it as read-only and never as writable, and the bubblewrap around the app-server mounts it back read-only, never
	 * writable, inside the results folder that it hides.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void readsLinkTargetsWithoutWritingThem() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			Path base = cleanup.add(Files.createTempDirectory(temporary, "cat-link-source-")).toRealPath();
			Files.writeString(base.resolve("notes.md"), "notes");
			Path source = Files.createDirectories(base.resolve("project"));
			Files.createSymbolicLink(source.resolve("notes.md"), Path.of("../notes.md"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			manager.copyProject(source, nestedSession);
			manager.snapshotProject(nestedSession);
			manager.create("codex");
			Path bin = cleanup.add(Files.createTempDirectory(temporary, "cat-bin-"));
			Path bwrap = Files.writeString(bin.resolve("bwrap"), "");
			requireThat(bwrap.toFile().setExecutable(true), "setExecutable").isTrue();
			Path configuration = nestedSession.directory().resolve("home/config.toml");
			CodexConnectionConfiguration.copy(nestedSession.directory().resolve("missing.toml"), configuration,
				nestedSession.directory().resolve("project"));
			Path linkTargets = NestedSessionManager.linkTargets(nestedSession.directory()).toRealPath();

			SessionSandbox sandbox = CodexRunner.sessionSandbox(configuration, manager, nestedSession, List.of(), List.of(),
				Platform.LINUX_X64, Map.of("PATH", bin.toString()), null, CodexRunner.appServerCommand("codex", false));

			List<String> command = sandbox.command();
			requireThat(command.get(0), "bubblewrap").isEqualTo(bwrap.toAbsolutePath().normalize().toString());
			int mount = command.indexOf(linkTargets.toString());
			requireThat(mount, "mount").isGreaterThan(0);
			requireThat(command.subList(mount - 1, mount + 2), "readOnlyMount").
				isEqualTo(List.of("--ro-bind", linkTargets.toString(), linkTargets.toString()));
			requireThat(command.subList(mount + 2, command.size()).contains(linkTargets.toString()), "otherMount").
				isFalse();
			JsonNode permissions = TomlMapper.builder().build().readTree(Files.readString(configuration)).
				path("permissions");
			Set<Path> reads = new HashSet<>();
			Set<Path> writes = new HashSet<>();
			// A profile has the entries of the profiles that it extends
			for (String profile = sandbox.profile(); permissions.has(profile);
				profile = permissions.path(profile).path("extends").stringValue(""))
			{
				JsonNode filesystem = permissions.path(profile).path("filesystem");
				for (String path : filesystem.propertyNames())
				{
					if (path.startsWith(":") || Files.notExists(Path.of(path)))
						continue;
					Path real = Path.of(path).toRealPath();
					switch (filesystem.get(path).stringValue())
					{
						case "read" -> reads.add(real);
						case "write" -> writes.add(real);
						default ->
						{
						}
					}
				}
			}
			requireThat(reads, "reads").contains(linkTargets);
			for (Path write : writes)
				requireThat(linkTargets.startsWith(write) || write.startsWith(linkTargets), "writes").isFalse();
		}
	}

	/**
	 * Verifies that on Linux a session that the caller denies nothing, started without bubblewrap on the caller's
	 * {@code PATH}, still runs under a permission profile that denies a path: the results folder. On stock
	 * ubuntu-24.04, Codex 0.160.0 fails to start the thread of such a session with {@code bwrap: loopback: Failed
	 * RTM_NEWADDR}, which the runner refuses, whereas a session without a denied path would start and fail every
	 * command with {@code bwrap: setting up uid map: Permission denied}.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void deniesResultsFolderWithoutCallerDenials() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			manager.snapshotProject(nestedSession);
			Path bin = cleanup.add(Files.createTempDirectory(temporary, "cat-bin-"));
			Path configuration = nestedSession.directory().resolve("home/config.toml");
			CodexConnectionConfiguration.copy(nestedSession.directory().resolve("missing.toml"), configuration,
				nestedSession.directory().resolve("project"));

			SessionSandbox sandbox = CodexRunner.sessionSandbox(configuration, manager, nestedSession, List.of(), List.of(),
				Platform.LINUX_X64, Map.of("PATH", bin.toString()), null, CodexRunner.appServerCommand("codex", false));

			JsonNode denied = TomlMapper.builder().build().readTree(Files.readString(configuration)).
				path("permissions").path(sandbox.profile()).path("filesystem");
			requireThat(denied.path(root.toRealPath().toString()).stringValue(""), "access").isEqualTo("deny");
		}
	}

	/**
	 * Verifies that on macOS and Windows a session that reads evidence under the results folder starts without
	 * bubblewrap on the caller's {@code PATH}, as before: its app-server command is not wrapped, and its permission
	 * profile lets it read the evidence while it denies the whole results folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void startsJudgeWithoutBubblewrapOffLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			manager.snapshotProject(nestedSession);
			NestedSession trial = manager.create("codex");
			Path evidence = Files.createDirectories(trial.directory().resolve("artifacts"));
			Path bin = cleanup.add(Files.createTempDirectory(temporary, "cat-bin-"));
			List<String> appServer = CodexRunner.appServerCommand("codex", false);

			for (Platform platform : List.of(Platform.MACOS_ARM64, Platform.WINDOWS_X64))
			{
				Path configuration = nestedSession.directory().resolve("home/config-" + platform.id() + ".toml");
				CodexConnectionConfiguration.copy(nestedSession.directory().resolve("missing.toml"), configuration,
					nestedSession.directory().resolve("project"));

				SessionSandbox sandbox = CodexRunner.sessionSandbox(configuration, manager, nestedSession, List.of(evidence),
					List.of(), platform, Map.of("PATH", bin.toString()), null, appServer);

				requireThat(sandbox.command(), platform + ".command").isEqualTo(appServer);
				JsonNode filesystem = TomlMapper.builder().build().readTree(Files.readString(configuration)).
					path("permissions").path(sandbox.profile()).path("filesystem");
				requireThat(filesystem.path(root.toRealPath().toString()).stringValue(""), platform + ".root").
					isEqualTo("deny");
			}
		}
	}

	/**
	 * Verifies that on Linux a session that reads no evidence under the results folder is denied the whole results
	 * folder as one path, besides the Codex session logs in its Codex folder and the paths that the caller denies, but
	 * not its own configuration there. The sandbox keeps the session's project and home writable inside the denied
	 * root, so the session reads them while every other nested session, including one that starts later, and the
	 * workspaces stay hidden. The session log directory exists so that the sandbox can deny it.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void deniesResultsFolderOnLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path directory = nestedSession.directory();
			manager.snapshotProject(nestedSession);
			Files.createDirectories(directory.resolve("home/.codex"));
			Files.writeString(directory.resolve("home/.codex/config.toml"), "");
			manager.create("codex");
			Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(), List.of(heldOut),
				Platform.LINUX_X64, Map.of());

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut, root,
				directory.resolve("home/.codex/sessions")));
			requireThat(Files.isDirectory(directory.resolve("home/.codex/sessions")), "sessionLogsExist").isTrue();
		}
	}

	/**
	 * Verifies that the commands of a session that may request sessions are not denied the directory where Codex logs
	 * the session's events, so that the prompt optimizer's {@code run} command, which its host session runs and which
	 * reads the session's rollout there to learn that the user interrupted the session's turn, finds the rollout
	 * instead of failing with an access denial. The session is denied only the results folder and the caller's paths.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void readableRolloutWhenRequestsAllowed() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(nestedSession, root.resolve("artifact"), 1,
				Platform.LINUX_X64, SessionRequestAccess.Shell.POSIX).orElseThrow();

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(), List.of(heldOut),
				Platform.LINUX_X64, Map.of(), requestAccess);

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut, root));
		}
	}

	/**
	 * Verifies that a session that may request no session stays denied the directory where Codex logs its events,
	 * because the session under test must not read the record of its own nested session, and no command of it starts a
	 * session that would need to read the log.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void deniedRolloutWhenNoRequestsAllowed() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(nestedSession, root.resolve("artifact"), 0,
				Platform.LINUX_X64, SessionRequestAccess.Shell.POSIX).orElseThrow();

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(), List.of(heldOut),
				Platform.LINUX_X64, Map.of(), requestAccess);

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut, root,
				nestedSession.directory().resolve("home/.codex/sessions")));
		}
	}

	/**
	 * Verifies that a session that a Codex session requests may read, but not write, the CAT version that Codex
	 * installed in the requesting session's home, such as a launcher that the request's prompt names, while its
	 * permission profile still denies it the rest of the requesting nested session, such as that session's login.
	 *
	 * @throws IOException if the nested sessions cannot be created
	 */
	@Test
	public void readsPluginOfRequestingSession() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-requester-plugin-")).
				toRealPath();
			Path artifact = root.resolve("artifact");
			Path results = root.resolve("results");
			NestedSessionManager firstManager = new NestedSessionManager(jvm.fileLocks(), results, Clock.systemUTC(),
				Yielding.fileReplacer());
			NestedSession first = firstManager.create("codex");
			SessionRequestAccess firstAccess = SessionRequestAccess.allowing(first, artifact, 1, Platform.current(),
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			Path firstHome = Files.createDirectories(first.directory().resolve("home"));
			Files.writeString(firstHome.resolve("config.toml"), """
				[plugins."cat@cat"]
				enabled = true
				""");
			Path version = firstHome.resolve("plugins/cache/cat/cat/1.0+b0123456789ab");
			Files.writeString(Files.createDirectories(version.resolve(".codex-plugin")).resolve("plugin.json"),
				"{\"name\": \"cat\"}\n");
			Path launcher = Files.writeString(Files.createDirectories(version.resolve("bin")).
				resolve("prompt-optimizer"), "launcher");
			Path login = Files.writeString(firstHome.resolve("auth.json"), "{}");
			NestedSessionManager nestedManager = new NestedSessionManager(jvm.fileLocks(),
				firstAccess.requestedResultsFolder(1), Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nested = nestedManager.create("codex");
			SessionRequestAccess nestedAccess = SessionRequestAccess.allowing(nested, artifact, 0, Platform.current(),
				SessionRequestAccess.Shell.POSIX).orElseThrow();
			Path configuration = nested.directory().resolve("home/config.toml");
			CodexConnectionConfiguration.copy(nested.directory().resolve("missing.toml"), configuration,
				nested.directory().resolve("project"));

			String profile = CodexRunner.addSessionProfile(configuration, nestedManager, nested, List.of(),
				SessionRequestBroker.resultsDenials(results, CodexRunner.installedCat(firstHome)), Platform.current(),
				Map.of(), nestedAccess);

			JsonNode permissions = TomlMapper.builder().build().readTree(Files.readString(configuration)).
				path("permissions");
			List<Path> denials = new ArrayList<>();
			List<Path> writes = new ArrayList<>();
			// A profile has the entries of the profiles that it extends
			for (String name = profile; permissions.has(name);
				name = permissions.path(name).path("extends").stringValue(""))
			{
				JsonNode filesystem = permissions.path(name).path("filesystem");
				for (String path : filesystem.propertyNames())
				{
					if (path.startsWith(":"))
						continue;
					switch (filesystem.get(path).stringValue())
					{
						case "deny" -> denials.add(Path.of(path));
						case "write" -> writes.add(Path.of(path));
						default ->
						{
						}
					}
				}
			}
			for (Path path : denials)
				requireThat(launcher.startsWith(path), "launcher.startsWith(" + path + ")").isFalse();
			for (Path path : writes)
				requireThat(launcher.startsWith(path), "launcher.startsWith(" + path + ")").isFalse();
			requireThat(denials.stream().anyMatch(login::startsWith), "loginDenied").isTrue();
		}
	}

	/**
	 * Verifies that a session's developer instructions first tell the model that the denied reads under the results
	 * folder do not cover its project or home, then give the agent's contract, if any. Codex lists the results folder
	 * among the denied reads, and without that note models answered that their own project was denied without trying to
	 * read it.
	 */
	@Test
	public void developerNoteExemptsProject()
	{
		Path root = Path.of("/results");
		Path project = Path.of("/results/codex/session-1/project");
		Path home = Path.of("/results/codex/session-1/home");
		String note = "The denied reads protect other sessions' projects in " + root + ". They do not apply to your " +
			"project " + project + ", your working directory, or to anything under it, nor to " + home + ": you have " +
			"full read and write access to both. Your project's files are not in your context; read one with a shell " +
			"command whenever a task needs it.";

		requireThat(CodexRunner.developerInstructions(root, project, home, ""), "withoutAgent").isEqualTo(note);
		requireThat(CodexRunner.developerInstructions(root, project, home, "Review the plan."), "withAgent").
			isEqualTo(note + "\n\nReview the plan.");
	}

	/**
	 * Verifies that the developer instructions of a session, which are the same whether or not it may request
	 * sessions, hold the note on denied reads and the agent's contract and no instructions on requesting a session,
	 * which the session reads in CAT's rules instead.
	 */
	@Test
	public void developerNoteAddsNoRequestInstructions()
	{
		// Requirement: R-I9
		Path root = Path.of("/results");
		Path project = Path.of("/results/codex/session-1/project");
		Path home = Path.of("/results/codex/session-1/home");

		requireThat(CodexRunner.developerInstructions(root, project, home, "Review the plan."), "instructions").
			doesNotContain("request-session").doesNotContain("CAT_REQUEST_SESSION").
			doesNotContain("--session-requests").
			endsWith("\n\nReview the plan.");
	}

	/**
	 * Verifies that on macOS and Windows a session is denied the whole results folder as one path, besides the Codex
	 * session logs and the login copy in its Codex folder and the paths that the caller denies. Their sandboxes keep
	 * the session's project and home readable and writable inside the denied root.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void deniesResultsFolderOutsideLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path directory = nestedSession.directory();
			manager.snapshotProject(nestedSession);
			Files.writeString(Files.createDirectories(directory.resolve("home/.codex")).resolve("auth.json"), "{}");
			manager.create("codex");
			Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			for (Platform platform : List.of(Platform.MACOS_ARM64, Platform.WINDOWS_X64))
			{
				List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(), List.of(heldOut),
					platform, Map.of());

				requireThat(Set.copyOf(deniedReads), platform + ".deniedReads").isEqualTo(Set.of(heldOut, root,
					directory.resolve("home/.codex/sessions"), directory.resolve("home/.codex/auth.json")));
			}
		}
	}

	/**
	 * Verifies that on macOS and Windows a session that validates evidence under the results folder is still denied the
	 * whole results folder as one path, because their sandboxes keep a read-only allowance readable inside a denied
	 * root.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void deniesResultsBesideEvidenceOutsideLinux() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path directory = nestedSession.directory();
			manager.snapshotProject(nestedSession);
			NestedSession evidence = manager.create("codex");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			for (Platform platform : List.of(Platform.MACOS_X64, Platform.WINDOWS_ARM64))
			{
				List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(evidenceArtifacts),
					List.of(heldOut), platform, Map.of());

				requireThat(Set.copyOf(deniedReads), platform + ".deniedReads").isEqualTo(Set.of(heldOut, root,
					directory.resolve("home/.codex/sessions")));
			}
		}
	}

	/**
	 * Verifies that on Linux a session that validates evidence under the results folder is denied each other nested
	 * session by its own path rather than the whole root, because the sandbox would hide read-only evidence inside a
	 * denied root. The session can still read its project, the rest of its home, except the login copy in its Codex
	 * folder, and the evidence that it validates.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void deniesEachSessionBesideEvidenceInRoot() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path root = cleanup.add(Files.createTempDirectory(temporary, "cat-results-"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path directory = nestedSession.directory();
			manager.snapshotProject(nestedSession);
			Files.writeString(Files.createDirectories(directory.resolve("home/.codex")).resolve("auth.json"), "{}");
			NestedSession other = manager.create("codex");
			NestedSession evidence = manager.create("codex");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));
			Path workspaces = Files.createDirectories(root.resolve("workspaces"));
			Path heldOut = cleanup.add(Files.createTempDirectory(temporary, "cat-held-out-"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(evidenceArtifacts),
				List.of(heldOut), Platform.LINUX_X64, Map.of());

			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(heldOut,
				directory.resolve("artifacts"), directory.resolve("session.json"), directory.resolve(".lock"),
				directory.resolve("initial-project"), directory.resolve("home/.codex/sessions"),
				directory.resolve("home/.codex/auth.json"), other.directory(), evidence.directory().resolve("project"),
				evidence.directory().resolve("home"), evidence.directory().resolve("session.json"),
				evidence.directory().resolve(".lock"), workspaces));
		}
	}

	/**
	 * Verifies that on Linux a session that validates evidence inside a project that the caller denies is denied each
	 * other file and directory of that project by its own path, each path once, because the sandbox would hide
	 * read-only evidence inside a denied directory. On macOS and Windows the session is denied the whole project, which
	 * holds the results folder, because their sandboxes keep the session's project, home and evidence readable inside
	 * it.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void deniesProjectBesideEvidence() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path project = cleanup.add(Files.createTempDirectory(temporary, "cat-user-project-"));
			Path instructions = Files.writeString(project.resolve("AGENTS.md"), "Follow the handbook.");
			Path documents = Files.createDirectories(project.resolve("docs"));
			Path session = Files.createDirectories(project.resolve(".cat/sessions/session-1"));
			Path otherSession = Files.createDirectories(project.resolve(".cat/sessions/session-2"));
			Path campaigns = Files.createDirectories(session.resolve("campaigns"));
			Path root = Files.createDirectories(session.resolve("optimizer-nested-sessions/codex"));
			Path heldOutRoot = Files.createDirectories(session.resolve("optimizer-nested-sessions/codex-held_out"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");
			Path directory = nestedSession.directory();
			manager.snapshotProject(nestedSession);
			NestedSession evidence = manager.create("codex");
			Path evidenceArtifacts = Files.createDirectories(evidence.directory().resolve("artifacts"));

			List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(evidenceArtifacts),
				List.of(project), Platform.LINUX_X64, Map.of());

			requireThat(deniedReads.size(), "deniedReads.size()").isEqualTo(Set.copyOf(deniedReads).size());
			requireThat(Set.copyOf(deniedReads), "deniedReads").isEqualTo(Set.of(instructions, documents,
				otherSession, campaigns, heldOutRoot, directory.resolve("artifacts"), directory.resolve("session.json"),
				directory.resolve(".lock"), directory.resolve("initial-project"), directory.resolve("home/.codex/sessions"),
				evidence.directory().resolve("project"), evidence.directory().resolve("home"),
				evidence.directory().resolve("session.json"), evidence.directory().resolve(".lock")));
			for (Platform platform : List.of(Platform.MACOS_ARM64, Platform.WINDOWS_X64))
			{
				deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(evidenceArtifacts),
					List.of(project), platform, Map.of());

				requireThat(Set.copyOf(deniedReads), platform + ".deniedReads").isEqualTo(Set.of(project,
					directory.resolve("home/.codex/sessions")));
			}
		}
	}

	/**
	 * Verifies that a session is not denied a path inside another denied directory, such as the held-out results folder
	 * inside the project, because the Linux sandbox fails to start when it must hide a directory inside one that it
	 * already hides, while a denied path inside the session's home stays denied.
	 *
	 * @throws IOException if the nested session cannot be created
	 */
	@Test
	public void skipsDeniedReadsInsideDeniedDirectory() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = jvm.temporaryDirectory();
			Path project = cleanup.add(Files.createTempDirectory(temporary, "cat-user-project-"));
			Path session = Files.createDirectories(project.resolve(".cat/sessions/session-1"));
			Path root = Files.createDirectories(session.resolve("optimizer-nested-sessions/codex"));
			Path heldOutRoot = Files.createDirectories(session.resolve("optimizer-nested-sessions/codex-held_out"));
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), root,
				Clock.systemUTC(), Yielding.fileReplacer());
			NestedSession nestedSession = manager.create("codex");

			for (Platform platform : List.of(Platform.LINUX_X64, Platform.MACOS_ARM64, Platform.WINDOWS_X64))
			{
				List<Path> deniedReads = CodexRunner.sessionDeniedReads(manager, nestedSession, List.of(),
					List.of(heldOutRoot, project), platform, Map.of());

				requireThat(deniedReads, platform + ".deniedReads").containsExactly(List.of(project,
					nestedSession.directory().resolve("home/.codex/sessions")));
			}
		}
	}

	/**
	 * Returns the {@code network_proxy} rows that Codex 0.160.0 prints in {@code codex features list}, and whether the
	 * runner starts a session with network hosts under each.
	 *
	 * @return the row, or an empty string when Codex prints none, and whether the session starts
	 */
	@DataProvider
	public Object[][] networkProxyRows()
	{
		return new Object[][]
		{
			{"network_proxy                            experimental       true\n", true},
			{"network_proxy                            experimental       false\n", false},
			{"network_proxy                            removed            false\n", false},
			{"", false}
		};
	}

	/**
	 * Verifies that the runner starts a session whose commands may reach some hosts only when {@code codex features
	 * list}, with the session's Codex home, shows Codex's network proxy on, since Codex silently ignores a feature
	 * that it no longer knows and would then let the commands reach every host. The rows around it are recorded from
	 * Codex 0.160.0.
	 *
	 * @param row    the {@code network_proxy} row
	 * @param starts {@code true} if the session starts
	 * @throws IOException if the check fails for a reason other than the refusal
	 */
	@Test(dataProvider = "networkProxyRows")
	public void startsOnlyWithNetworkProxy(String row, boolean starts) throws IOException
	{
		String features = """
			multi_agent                              stable             true
			multi_agent_v2                           stable             false
			%snon_interactive_approvals               experimental       false
			""".formatted(row);
		List<List<String>> commands = new ArrayList<>();
		CodexRunner.CodexOutput codex = arguments ->
		{
			commands.add(arguments);
			if (arguments.equals(List.of("--version")))
				return "codex-cli 0.161.0\n(See --build-info for more information)\n";
			return features;
		};

		if (starts)
		{
			CodexRunner.requireNetworkProxy(codex, List.of("--enable", "hooks"), Set.of("chatgpt.com"));
			requireThat(commands, "commands").isEqualTo(List.of(List.of("features", "list", "--enable", "hooks")));
			return;
		}
		SessionRefusedException refusal = expectThrows(SessionRefusedException.class,
			() -> CodexRunner.requireNetworkProxy(codex, List.of("--enable", "hooks"),
				Set.of("chatgpt.com", "api.openai.com")));
		requireThat(refusal.getMessage(), "message").isEqualTo("Codex 0.161.0 no longer limits the session's " +
			"network to api.openai.com, chatgpt.com (`features.network_proxy` is not available), so CAT does not " +
			"start the trial.");
	}

	/**
	 * Verifies that a session whose commands may reach no host starts without asking Codex for its features, since its
	 * network is off without the proxy.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void skipsNetworkProxyWithoutHosts() throws IOException
	{
		CodexRunner.requireNetworkProxy(arguments ->
		{
			throw new IOException("Codex was asked " + arguments);
		}, List.of(), Set.of());
	}

	/**
	 * Verifies that Codex's start failure when the system lets bubblewrap create no network namespace, as stock
	 * Ubuntu 24.04 does, refuses the session with Codex's reason, while other start failures stay failures. The
	 * failure text is recorded from Codex 0.160.0 on a hosted ubuntu-24.04 runner.
	 */
	@Test
	public void refusesSandboxThatCannotStart()
	{
		IOException loopback = new IOException("Codex app-server did not start the thread: thread/start failed: " +
			"error creating thread: failed to load AGENTS.md instructions for environment `local`: fs sandbox helper " +
			"failed with status exit status: 1: bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted");
		IOException other = new IOException("Codex app-server did not start the thread: model not found");

		IOException refusal = CodexRunner.refusalOf(loopback);
		requireThat(refusal, "refusal").isInstanceOf(SessionRefusedException.class);
		requireThat(refusal.getMessage(), "message").contains("bwrap: loopback: Failed RTM_NEWADDR: Operation not " +
			"permitted");
		requireThat(refusal.getCause(), "cause").isReferenceEqualTo(loopback, "loopback");
		requireThat(CodexRunner.refusalOf(other), "failure").isReferenceEqualTo(other, "other");
	}

	/**
	 * Verifies that an app-server that cannot start because the system lets bubblewrap create no user namespace, as
	 * stock Ubuntu 24.04 does, refuses the session with that reason. The failure text is recorded from bubblewrap on a
	 * hosted ubuntu-24.04 runner.
	 */
	@Test
	public void refusesSandboxWithoutUserNamespaces()
	{
		IOException uidMap = new IOException("Codex app-server ended before responding to request 1: bwrap: " +
			"setting up uid map: Permission denied");

		IOException refusal = CodexRunner.refusalOf(uidMap);
		requireThat(refusal, "refusal").isInstanceOf(SessionRefusedException.class);
		requireThat(refusal.getMessage(), "message").contains("bwrap: setting up uid map: Permission denied");
		requireThat(refusal.getCause(), "cause").isReferenceEqualTo(uidMap, "uidMap");
	}

	/**
	 * Returns the launch options that name a source home and a gateway login class.
	 *
	 * @param source     the source home
	 * @param loginClass the declared class, or {@code null} to declare none
	 * @return the options
	 */
	private static AuthenticationOptions named(Path source, HostLogin loginClass)
	{
		return new AuthenticationOptions(Optional.of(source), Optional.ofNullable(loginClass), false);
	}

	/**
	 * Creates a source home whose {@code .codex/auth.json} holds an API key, unless it holds one already, and reads its
	 * authentication.
	 *
	 * @param root the folder that receives the source home
	 * @param name the source home's name
	 * @return the authentication
	 * @throws IOException if the fixture cannot be written or the source is refused
	 */
	private static CodexAuthentication apiKeySource(Path root, String name) throws IOException
	{
		Path source = CodexSourceHomes.create(root, name);
		if (!Files.exists(source.resolve(".codex/auth.json")))
			CodexSourceHomes.apiKeyLogin(source.resolve(".codex"));
		return CodexAuthentication.read(named(source, null), Map.of(), Platform.current());
	}

	/**
	 * Creates a source home whose selected provider prints its token with a command and that holds no
	 * {@code auth.json}, and reads its authentication.
	 *
	 * @param root the folder that receives the source home
	 * @param name the source home's name
	 * @return the authentication
	 * @throws IOException if the fixture cannot be written or the source is refused
	 */
	private static CodexAuthentication tokenCommandSource(Path root, String name) throws IOException
	{
		Path source = CodexSourceHomes.create(root, name);
		CodexSourceHomes.secret(source.resolve("key"));
		CodexSourceHomes.configuration(source.resolve(".codex"), """
			model_provider = "gateway"
			[model_providers.gateway]
			base_url = "https://gateway.example/v1"
			[model_providers.gateway.auth]
			command = "sh"
			args = ["-c", "cat ~/key"]
			""");
		return CodexAuthentication.read(named(source, HostLogin.API_KEY), Map.of(), Platform.current());
	}
}
