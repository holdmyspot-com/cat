package com.holdmyspot.cat.codex.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the trust that the Codex integration records in Codex's {@code config.toml} for the hooks of CAT's plugin, so
 * that a fresh Codex runs them without the user trusting each by hand.
 */
public final class HookTrustTest
{
	/**
	 * Reads and writes the hooks files of the fixtures.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The hash that Codex recorded in its own {@code config.toml}, when the user trusted the SessionStart hook of
	 * {@link #CAT_HOOKS}, as it was installed in a Codex home.
	 */
	private static final String SESSION_START_HASH =
		"sha256:04a2f09204425f475b0ac90a822549742613319031e4d7e928c353934dd6cd00";
	/**
	 * The hash that Codex recorded for the PostToolUse hook of {@link #CAT_HOOKS}.
	 */
	private static final String POST_TOOL_USE_HASH =
		"sha256:db8770d52ba063c0da6c2ee84aed37b32e85fff6b23ab745e177f0d79677c39c";
	/**
	 * The hash that Codex recorded for the SubagentStart hook of {@link #CAT_HOOKS}.
	 */
	private static final String SUBAGENT_START_HASH =
		"sha256:45fb66653002cb5756d10364ee9af68a6baaa74c1b8394bee029b1c1340cc0ba";
	/**
	 * The key of the SessionStart hook in Codex's configuration.
	 */
	private static final String SESSION_START = "cat@cat:hooks/hooks.json:session_start:0:0";
	/**
	 * The key of the PostToolUse hook in Codex's configuration.
	 */
	private static final String POST_TOOL_USE = "cat@cat:hooks/hooks.json:post_tool_use:0:0";
	/**
	 * The key of the SubagentStart hook in Codex's configuration.
	 */
	private static final String SUBAGENT_START = "cat@cat:hooks/hooks.json:subagent_start:0:0";
	/**
	 * The hooks of CAT's Codex plugin as Codex trusted them when it recorded the hashes above, without the Windows
	 * commands, which Codex ignores outside Windows.
	 */
	private static final String CAT_HOOKS = """
		{
			"hooks": {
				"SessionStart": [
					{
						"matcher": "^(startup|resume|clear|compact)$",
						"hooks": [
							{
								"type": "command",
								"command": "\\"$PLUGIN_ROOT/hooks/session-start.sh\\"",
								"additionalContextLimit": 0
							}
						]
					}
				],
				"PostToolUse": [
					{
						"matcher": "^Bash$",
						"hooks": [
							{
								"type": "command",
								"command": "\\"$PLUGIN_ROOT/hooks/post-tool-use.sh\\""
							}
						]
					}
				],
				"SubagentStart": [
					{
						"hooks": [
							{
								"type": "command",
								"command": "\\"$PLUGIN_ROOT/hooks/subagent-start.sh\\"",
								"additionalContextLimit": 0
							}
						]
					}
				]
			}
		}
		""";
	/**
	 * The plugin version of the installed plugin.
	 */
	private static final String VERSION = "1.0+b0123456789ab";
	/**
	 * The plugin version of a later build.
	 */
	private static final String LATER_VERSION = "1.0+bfedcba987654";

	/**
	 * Installing records, for each hook of CAT's plugin, the hash that Codex computes for it, so Codex trusts the hooks
	 * of a fresh install. The record follows the hooks' order in the file and holds only the hooks' trust.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void trustsEachHookWithCodexsHash() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);

			provider().installPermissions(home, installer, replacer());

			requireThat(Files.readString(home.resolve("config.toml")), "configuration").isEqualTo(
				trusted(SESSION_START, SESSION_START_HASH) + "\n" + trusted(POST_TOOL_USE, POST_TOOL_USE_HASH) + "\n" +
					trusted(SUBAGENT_START, SUBAGENT_START_HASH));
		}
	}

	/**
	 * The hash is Codex's identity of the hook that runs, so a plugin whose hooks declare the same command and settings
	 * in another form, and a Windows command that Codex ignores on this platform, get the same trust.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void sameHookRunsGetSameTrust() throws IOException
	{
		String posix = "\"$PLUGIN_ROOT/hooks/session-start.sh\"";
		String windows = "& \"$env:PLUGIN_ROOT\\hooks\\session-start.cmd\"";
		String command = posix;
		if (Platform.current().isWindows())
			command = windows;
		ObjectNode declared = JSON.createObjectNode();
		declared.put("type", "command").put("additionalContextLimit", 0).put("command", posix).
			put("commandWindows", windows).put("timeout", 600).put("async", false);
		ObjectNode plain = JSON.createObjectNode();
		plain.put("type", "command").put("command", command).put("additionalContextLimit", 0);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path first = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path second = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));

			provider().installPermissions(first, plugin(first, VERSION, sessionStart(declared)), replacer());
			provider().installPermissions(second, plugin(second, VERSION, sessionStart(plain)), replacer());

			requireThat(Files.readString(first.resolve("config.toml")), "declared").
				isEqualTo(Files.readString(second.resolve("config.toml")));
		}
	}

	/**
	 * A changed command, matcher or setting gives the hook another hash, so Codex asks the user again for a hook that
	 * CAT did not install.
	 *
	 * @param changed hooks that differ from {@link #CAT_HOOKS} in one setting of the SessionStart hook
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "changedHooks")
	public void changedHookGetsAnotherHash(String changed) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));

			provider().installPermissions(home, plugin(home, VERSION, changed), replacer());

			requireThat(Files.readString(home.resolve("config.toml")), "configuration").
				doesNotContain(SESSION_START_HASH).contains(POST_TOOL_USE_HASH).contains(SUBAGENT_START_HASH);
		}
	}

	/**
	 * Supplies hooks that change the SessionStart hook of CAT's plugin.
	 *
	 * @return the hooks
	 */
	@DataProvider
	public Object[][] changedHooks()
	{
		return new Object[][]
			{
				{CAT_HOOKS.replace("session-start.sh", "start.sh")},
				{CAT_HOOKS.replace("^(startup|resume|clear|compact)$", "^startup$")},
				{CAT_HOOKS.replaceFirst("\"type\": \"command\",", "\"type\": \"command\", \"timeout\": 30,")}
			};
	}

	/**
	 * Installing again adds nothing, and an install that finds the user's own settings, a disabled CAT hook and the
	 * trust of another plugin's hook keeps them and records only the missing hashes.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void keepsTheUsersSettings() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);
			Path configuration = home.resolve("config.toml");
			String original = """
				model = "gpt" # mine

				[hooks.state."other@market:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:%s"

				[hooks.state."%s"]
				enabled = false

				[plugins."cat@cat"]
				enabled = true
				""".formatted("b".repeat(64), SESSION_START);
			Files.writeString(configuration, original);

			Map<String, List<String>> added = provider().installPermissions(home, installer, replacer());

			String edited = original.replace("enabled = false\n", "trusted_hash = \"" + SESSION_START_HASH +
				"\"\nenabled = false\n") + "\n" + trusted(POST_TOOL_USE, POST_TOOL_USE_HASH) + "\n" +
				trusted(SUBAGENT_START, SUBAGENT_START_HASH);
			requireThat(Files.readString(configuration), "edited").isEqualTo(edited);
			requireThat(added.values().stream().flatMap(List::stream).filter(value -> value.startsWith("sha256:")).
				toList(), "recorded").
				containsExactly(List.of(SESSION_START_HASH, POST_TOOL_USE_HASH, SUBAGENT_START_HASH));
			requireThat(provider().installPermissions(home, installer, replacer()), "repeated").isEmpty();
			requireThat(Files.readString(configuration), "repeatedContent").isEqualTo(edited);
		}
	}

	/**
	 * Uninstalling removes the trust that the install recorded and leaves the file as it was, whether the install
	 * appended a table or added the hash to a table that the user's settings held.
	 *
	 * @param original the existing {@code config.toml}
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "originals")
	public void uninstallRestoresConfiguration(String original) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, original);
			Map<String, List<String>> added = provider().installPermissions(home, plugin(home, VERSION, CAT_HOOKS),
				replacer());
			requireThat(Files.readString(configuration), "installed").isNotEqualTo(original);

			provider().removePermissions(home, added, replacer());

			requireThat(Files.readString(configuration), "removed").isEqualTo(original);
		}
	}

	/**
	 * Supplies configurations that an install changes in different ways.
	 *
	 * @return the configurations
	 */
	@DataProvider
	public Object[][] originals()
	{
		return new Object[][]
			{
				{"model = \"gpt\"\n"},
				{"model = \"gpt\"\n\n[hooks.state.\"other@market:hooks/hooks.json:stop:0:0\"]\nenabled = false\n"},
				{"[hooks.state.\"" + SESSION_START + "\"]\nenabled = true\n\n[plugins.\"cat@cat\"]\nenabled = true\n"},
				{"[hooks]\n\n[hooks.state]\n"},
				{"model = \"gpt\"\n\n[hooks.state.\"" + POST_TOOL_USE + "\"]\nenabled = false\n\n[tui]\ntheme = \"x\"\n"}
			};
	}

	/**
	 * A hash that the user already recorded for a CAT hook stays after uninstall, because the install did not write it.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void uninstallKeepsHashTheUserRecorded() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			String theirs = trusted(SESSION_START, SESSION_START_HASH);
			Files.writeString(configuration, theirs);

			Map<String, List<String>> added = provider().installPermissions(home, plugin(home, VERSION, CAT_HOOKS),
				replacer());
			requireThat(Files.readString(configuration), "installed").contains(POST_TOOL_USE_HASH).
				contains(SUBAGENT_START_HASH);
			provider().removePermissions(home, added, replacer());

			requireThat(Files.readString(configuration), "removed").isEqualTo(theirs);
		}
	}

	/**
	 * Uninstalling leaves a hash that the user changed after the install, and says which entry it left.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void uninstallReportsHashTheUserChanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			Map<String, List<String>> added = provider().installPermissions(home, plugin(home, VERSION, CAT_HOOKS),
				replacer());
			String changedHash = "sha256:" + "c".repeat(64);
			Files.writeString(configuration, Files.readString(configuration).replace(POST_TOOL_USE_HASH, changedHash));

			provider().removePermissions(home, added, replacer());

			requireThat(Files.readString(configuration), "removed").isEqualTo(trusted(POST_TOOL_USE, changedHash));
			requireThat(provider().describeRetained(home, added).toString(), "retained").contains(POST_TOOL_USE).
				doesNotContain(SESSION_START).doesNotContain(SUBAGENT_START);
		}
	}

	/**
	 * Updating to a build whose hooks differ replaces the hashes that went stale, whether the earlier install recorded
	 * them or the user trusted the earlier hook by hand, drops the trust of a hook that the plugin no longer has, and
	 * leaves the trust of other plugins and the user's other settings in place.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void updateReplacesStaleHashes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path locks = home.resolveSibling("user/.cat/locks");
			Path configuration = home.resolve("config.toml");
			String other = trusted("other@market:hooks/hooks.json:session_start:0:0", "sha256:" + "b".repeat(64));
			String staleByHand = "sha256:" + "d".repeat(64);
			Files.writeString(configuration, "model = \"gpt\"\n\n" + other + "\n" + trusted(SUBAGENT_START,
				staleByHand));
			HarnessIntegration provider = provider();
			PluginInstall install = new PluginInstall(provider, _ -> new CommandResult(0, ""), replacer());
			HarnessInstallation installation = provider.installation(home);
			Path earlier = plugin(home, VERSION, CAT_HOOKS);
			install.installEntries(home, earlier, installation, List.of(), locks, locks.resolveSibling("sessions"),
				false);
			requireThat(Files.readString(configuration), "installed").contains(SESSION_START_HASH).
				contains(POST_TOOL_USE_HASH).contains(SUBAGENT_START_HASH).doesNotContain(staleByHand);
			ObjectNode withoutPostToolUse = (ObjectNode) JSON.readTree(CAT_HOOKS);
			((ObjectNode) withoutPostToolUse.get("hooks")).remove("PostToolUse");
			String later = JSON.writeValueAsString(withoutPostToolUse).replace("session-start.sh", "start.sh");
			Path newer = plugin(home, LATER_VERSION, later);

			PluginInstall.InstalledEntries entries = install.installEntries(home, newer, installation, List.of(),
				locks, locks.resolveSibling("sessions"), false);

			String content = Files.readString(configuration);
			requireThat(content, "updated").contains("model = \"gpt\"").contains(other).
				doesNotContain(SESSION_START_HASH).doesNotContain(POST_TOOL_USE_HASH).
				contains(SUBAGENT_START_HASH).doesNotContain(POST_TOOL_USE);
			requireThat(content.split("\\[hooks\\.state\\.\"cat@cat:", -1).length - 1, "catEntries").isEqualTo(2);
			requireThat(entries.recorded().values().stream().flatMap(List::stream).filter(
				value -> value.startsWith("sha256:")).count(), "recorded").isEqualTo(2L);
		}
	}

	/**
	 * The hooks file that the plugin's manifest names decides the key of each hook.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void followsManifestsHooksFile() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);
			Path folder = installer.getParent().getParent();
			Files.createDirectories(folder.resolve("config"));
			Files.move(folder.resolve("hooks/hooks.json"), folder.resolve("config/hooks.json"));
			Files.writeString(folder.resolve(".codex-plugin/plugin.json"),
				"{\"name\": \"cat\", \"hooks\": \"./config/hooks.json\"}");

			provider().installPermissions(home, installer, replacer());

			requireThat(Files.readString(home.resolve("config.toml")), "configuration").
				contains("[hooks.state.\"cat@cat:config/hooks.json:session_start:0:0\"]").
				contains("[hooks.state.\"cat@cat:config/hooks.json:subagent_start:0:0\"]");
		}
	}

	/**
	 * A hook that Codex normalizes differently from the hooks that the installer hashes, and a handler that is not a
	 * command, get no trust, so Codex still asks the user to review them.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void leavesOtherHooksForTheUserToReview() throws IOException
	{
		String hooks = """
			{"hooks": {
				"Stop": [{"hooks": [{"type": "command", "command": "./stop.sh"}]}],
				"SessionEnd": [{"hooks": [{"type": "command", "command": "./end.sh"}]}],
				"SessionStart": [{"hooks": [
					{"type": "agent", "command": "./agent.sh"},
					{"type": "command", "command": "./start.sh"}]}]
			}}
			""";
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));

			provider().installPermissions(home, plugin(home, VERSION, hooks), replacer());

			String configuration = Files.readString(home.resolve("config.toml"));
			requireThat(configuration, "configuration").contains("cat@cat:hooks/hooks.json:session_start:0:1").
				doesNotContain(":stop:").doesNotContain(":session_end:").doesNotContain("session_start:0:0");
		}
	}

	/**
	 * Install refuses, changing nothing, a configuration whose hook state it cannot edit safely.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void refusesUneditableHookState() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path configuration = home.resolve("config.toml");
			String original = "[hooks]\nstate = { \"x@y:hooks/hooks.json:stop:0:0\" = { enabled = false } }\n";
			Files.writeString(configuration, original);
			Path installer = plugin(home, VERSION, CAT_HOOKS);

			try
			{
				provider().installPermissions(home, installer, replacer());
				throw new AssertionError("The install did not refuse the configuration");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(configuration.toString()).contains("hooks.state");
			}
			requireThat(Files.readString(configuration), "content").isEqualTo(original);
		}
	}

	/**
	 * An install tells the user that CAT trusted its hooks, because the trust changes what Codex runs without asking.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void installTellsTheUser() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Map<String, List<String>> added = provider().installPermissions(home, plugin(home, VERSION, CAT_HOOKS),
				replacer());

			List<String> sentences = provider().describeAdded(home, added);

			requireThat(sentences.toString(), "sentences").contains(home.resolve("config.toml").toString()).
				contains("hooks");
			requireThat(provider().describeAdded(home, Map.of()), "withoutHooks").isEmpty();
		}
	}

	/**
	 * A plugin without hooks needs no trust, and an install of it leaves the configuration alone.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void pluginWithoutHooksNeedsNoTrust() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = home.resolve("plugins/cache/cat/cat").resolve(VERSION).resolve("bin/installer");
			if (Platform.current().isWindows())
				installer = installer.resolveSibling("installer.ps1");

			provider().installPermissions(home, installer, replacer());

			requireThat(Files.exists(home.resolve("config.toml")), "configurationExists").isFalse();
		}
	}

	/**
	 * Returns the table that records the trust of a hook.
	 *
	 * @param key  the hook's key in Codex's configuration
	 * @param hash the hash that Codex trusts
	 * @return the table, ending with a line terminator
	 */
	private static String trusted(String key, String hash)
	{
		return "[hooks.state.\"" + key + "\"]\ntrusted_hash = \"" + hash + "\"\n";
	}

	/**
	 * Returns the hooks file of a plugin whose only hook is a SessionStart handler.
	 *
	 * @param handler the handler
	 * @return the hooks file
	 */
	private static String sessionStart(ObjectNode handler)
	{
		ObjectNode group = JSON.createObjectNode().put("matcher", "^(startup|resume|clear|compact)$");
		group.putArray("hooks").add(handler);
		ObjectNode file = JSON.createObjectNode();
		file.putObject("hooks").putArray("SessionStart").add(group);
		return JSON.writeValueAsString(file);
	}

	/**
	 * Writes the hooks of CAT's plugin into the folder that Codex gives a plugin version.
	 *
	 * @param home    the Codex home
	 * @param version the plugin version
	 * @param hooks   the content of the plugin's {@code hooks/hooks.json}
	 * @return the plugin version's installer launcher
	 * @throws IOException if a file cannot be written
	 */
	private static Path plugin(Path home, String version, String hooks) throws IOException
	{
		Path folder = home.resolve("plugins/cache/cat/cat").resolve(version);
		Files.createDirectories(folder.resolve(".codex-plugin"));
		Files.writeString(folder.resolve(".codex-plugin/plugin.json"), "{\"name\": \"cat\", \"hooks\": " +
			"\"./hooks/hooks.json\"}");
		Files.createDirectories(folder.resolve("hooks"));
		Files.writeString(folder.resolve("hooks/hooks.json"), hooks);
		Path installer = folder.resolve("bin/installer");
		if (Platform.current().isWindows())
			installer = installer.resolveSibling("installer.ps1");
		return installer;
	}

	/**
	 * Locates build-owned fixture storage passed explicitly by Maven.
	 *
	 * @return a directory beneath this module's target directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path testRoot() throws IOException
	{
		return Files.createDirectories(Path.of(System.getProperty("cat.installer.test.directory")).toAbsolutePath());
	}

	/**
	 * Returns a replacer whose test-owned files never need a retry wait.
	 *
	 * @return the replacer
	 */
	private static FileReplacer replacer()
	{
		return new FileReplacer(Platform.current(), () ->
		{
		}, Clock.systemUTC());
	}

	/**
	 * Finds the Codex integration through the same registration that the installer consumes.
	 *
	 * @return the Codex integration
	 */
	private static HarnessIntegration provider()
	{
		return ServiceLoader.load(HarnessIntegration.class).stream().map(ServiceLoader.Provider::get).
			filter(provider -> provider.harness().equals("codex")).findFirst().
			orElseThrow(() -> new AssertionError("The Codex harness integration service is missing"));
	}
}
