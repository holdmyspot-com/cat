package com.holdmyspot.cat.codex.installer.test;

import static com.holdmyspot.cat.common.installer.contract.test.HarnessIntegrationContract.permissionEnvironment;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installation.HarnessInstallation;
import com.holdmyspot.cat.common.installer.activation.EntryEffect;
import com.holdmyspot.cat.common.installer.activation.EntryGroup;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.activation.HarnessIntegration;
import com.holdmyspot.cat.common.installer.engine.PluginInstall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Supplier;
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
	 * Waits for nothing, so that a deletion that fails is not retried at length.
	 */
	private static final Waiting NO_WAIT = interval -> () ->
	{
	};
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
	 * The hash of the installed files of a build of CAT that Codex listed for each of its hooks, the part of the hook's
	 * {@code currentHash} that Codex records as {@code trusted_content_hash}.
	 */
	private static final String CONTENT_HASH =
		"sha256:14078236a49ad16a69be2d3e4fe17f0039b96ba483b22ab56d011fe2e3a110eb";
	/**
	 * The {@code trusted_content_hash} that the user's review of the hooks of an earlier build recorded.
	 */
	private static final String EARLIER_CONTENT_HASH =
		"sha256:bba906b15dd71a347e8603e5f96a6649affb73545a73454bdf0d6b30851b7453";
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
	 * Hook trust is recorded before configuration replacement, so rejecting ownership leaves trust unchanged.
	 *
	 * @throws IOException if fixtures cannot be written or ownership is rejected
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "record rejected")
	public void rejectedHookRecordPreventsTrustWrite() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "hook-record-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "# user configuration\n");
			try
			{
				provider().installPermissions(permissionEnvironment(home), home, installer, replacer(), entries ->
				{
					if (entries.keySet().stream().anyMatch(key -> key.startsWith("config.toml#hooks.state.")))
						throw new IOException("record rejected");
				});
			}
			finally
			{
				requireThat(Files.readString(configuration), "configuration").isEqualTo("# user configuration\n");
			}
		}
	}

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

			provider().installPermissions(permissionEnvironment(home), home, installer, replacer(),
				entries ->
				{
				});

			requireThat(Files.readString(home.resolve("config.toml")), "configuration").isEqualTo(
				trusted(SESSION_START, SESSION_START_HASH) + "\n" + trusted(POST_TOOL_USE, POST_TOOL_USE_HASH) + "\n" +
					trusted(SUBAGENT_START, SUBAGENT_START_HASH));
		}
	}

	/**
	 * The installer's report lists the hooks whose trust install recorded as hooks that run without review, by their
	 * keys, and lists exactly the setting that records each hook's hash.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void reportsTrustedHooks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);

			Map<String, List<String>> recorded = provider().installPermissions(permissionEnvironment(home),
				home, installer, replacer(),
					entries ->
					{
					});
			List<EntryGroup> hooks = provider().describeEntries(recorded, installer).stream().
				filter(group -> group.effect() == EntryEffect.HOOKS_RUN_WITHOUT_REVIEW).toList();

			requireThat(hooks.size(), "groups").isEqualTo(1);
			EntryGroup group = hooks.getFirst();
			requireThat(group.file(), "file").isEqualTo(Path.of("config.toml"));
			requireThat(group.setting(), "setting").isEqualTo("hooks.state");
			requireThat(group.items(), "items").containsExactly(List.of(SESSION_START, POST_TOOL_USE, SUBAGENT_START));
			requireThat(group.entries(), "entries").containsExactly(List.of(
				"hooks.state.\"" + SESSION_START + "\".trusted_hash = \"" + SESSION_START_HASH + "\"",
				"hooks.state.\"" + POST_TOOL_USE + "\".trusted_hash = \"" + POST_TOOL_USE_HASH + "\"",
				"hooks.state.\"" + SUBAGENT_START + "\".trusted_hash = \"" + SUBAGENT_START_HASH + "\""));
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

			provider().installPermissions(permissionEnvironment(first),
				first, plugin(first, VERSION, sessionStart(declared)), replacer(),
					entries ->
					{
					});
			provider().installPermissions(permissionEnvironment(second),
				second, plugin(second, VERSION, sessionStart(plain)), replacer(),
					entries ->
					{
					});

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

			Map<String, List<String>> added = provider().installPermissions(permissionEnvironment(home), home,
				plugin(home, VERSION, changed), replacer(),
					entries ->
					{
					});

			List<String> hashes = added.get("config.toml#hooks.state.\"" + SESSION_START + "\".trusted_hash");
			requireThat(hashes.size(), "changedHookHashCount").isEqualTo(1);
			String hash = hashes.getFirst();
			requireThat(hash.matches("sha256:[0-9a-f]{64}"), "changedHookHash").isTrue();
			requireThat(hash, "changedHookHash").isNotEqualTo(SESSION_START_HASH);
			requireThat(Files.readString(home.resolve("config.toml")), "configuration").
				contains(trusted(SESSION_START, hash));

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

			Map<String, List<String>> added = provider().installPermissions(permissionEnvironment(home),
				home, installer, replacer(),
					entries ->
					{
					});

			String edited = original.replace("enabled = false\n", "trusted_hash = \"" + SESSION_START_HASH +
				"\"\nenabled = false\n") + "\n" + trusted(POST_TOOL_USE, POST_TOOL_USE_HASH) + "\n" +
				trusted(SUBAGENT_START, SUBAGENT_START_HASH);
			requireThat(Files.readString(configuration), "edited").isEqualTo(edited);
			requireThat(added.values().stream().flatMap(List::stream).filter(value -> value.startsWith("sha256:")).
				toList(), "recorded").
				containsExactly(List.of(SESSION_START_HASH, POST_TOOL_USE_HASH, SUBAGENT_START_HASH));
			requireThat(provider().installPermissions(permissionEnvironment(home),
				home, installer, replacer(),
					entries ->
					{
					}), "repeated").isEmpty();
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
			Map<String, List<String>> added = provider().installPermissions(permissionEnvironment(home),
				home, plugin(home, VERSION, CAT_HOOKS),
				replacer(),
					entries ->
					{
					});
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

			Map<String, List<String>> added = provider().installPermissions(permissionEnvironment(home),
				home, plugin(home, VERSION, CAT_HOOKS),
				replacer(),
					entries ->
					{
					});
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
			Map<String, List<String>> added = provider().installPermissions(permissionEnvironment(home),
				home, plugin(home, VERSION, CAT_HOOKS),
				replacer(),
					entries ->
					{
					});
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
			PluginInstall install = new PluginInstall(provider, CodexProgram.cli(home), replacer(), NO_WAIT);
			HarnessInstallation installation = provider.installation(home);
			Path earlier = plugin(home, VERSION, CAT_HOOKS);
			install.installEntries(permissionEnvironment(home),
				home, earlier, installation, List.of(), locks, locks.resolveSibling("sessions"),
				List.of(), false);
			requireThat(Files.readString(configuration), "installed").contains(SESSION_START_HASH).
				contains(POST_TOOL_USE_HASH).contains(SUBAGENT_START_HASH).doesNotContain(staleByHand);
			ObjectNode withoutPostToolUse = (ObjectNode) JSON.readTree(CAT_HOOKS);
			((ObjectNode) withoutPostToolUse.get("hooks")).remove("PostToolUse");
			String later = JSON.writeValueAsString(withoutPostToolUse).replace("session-start.sh", "start.sh");
			Path newer = plugin(home, LATER_VERSION, later);

			PluginInstall.InstalledEntries entries = install.installEntries(permissionEnvironment(home),
				home, newer, installation, List.of(),
				locks, locks.resolveSibling("sessions"), List.of(), false);

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

			provider().installPermissions(permissionEnvironment(home), home, installer, replacer(),
				entries ->
				{
				});

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

			provider().installPermissions(permissionEnvironment(home), home, plugin(home, VERSION, hooks), replacer(),
				entries ->
				{
				});

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
				provider().installPermissions(permissionEnvironment(home), home, installer, replacer(),
					entries ->
					{
					});
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
			Map<String, List<String>> added = provider().installPermissions(permissionEnvironment(home),
				home, plugin(home, VERSION, CAT_HOOKS),
				replacer(),
					entries ->
					{
					});

			List<String> sentences = provider().describeAdded(home, added);

			requireThat(sentences.toString(), "sentences").contains(home.resolve("config.toml").toString()).
				contains("hooks");
			requireThat(provider().describeAdded(home, Map.of()), "withoutHooks").isEmpty();
		}
	}

	/**
	 * A plugin whose manifest names no hooks file and that ships none needs no trust, and an install of it leaves the
	 * configuration alone.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void pluginWithoutHooksNeedsNoTrust() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path folder = home.resolve("plugins/cache/cat/cat").resolve(VERSION);
			Files.createDirectories(folder.resolve(".codex-plugin"));
			Files.writeString(folder.resolve(".codex-plugin/plugin.json"), "{\"name\": \"cat\"}");
			Path installer = folder.resolve("bin/installer");
			if (Platform.current().isWindows())
				installer = installer.resolveSibling("installer.ps1");

			provider().installPermissions(permissionEnvironment(home), home, installer, replacer(),
				entries ->
				{
				});

			requireThat(Files.exists(home.resolve("config.toml")), "configurationExists").isFalse();
		}
	}

	/**
	 * After an update whose hook declarations did not change, the tables of CAT's hooks still hold the content hash of
	 * the earlier build, so Codex lists them as modified. Install records the content hash that Codex lists for each
	 * of CAT's hooks of the installed version, changes no other byte of the file, and says so.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void recordsCodexsContentHashOfCatsHooks() throws IOException
	{
		// Requirement: R-I21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);
			Path hooksFile = installer.getParent().getParent().resolve("hooks/hooks.json");
			Path configuration = home.resolve("config.toml");
			String original = "# The user's settings\nmodel = \"gpt\"\n\n" +
				"[hooks.state.\"" + SESSION_START + "\"]\ntrusted_hash = \"" + SESSION_START_HASH + "\"\nenabled = true\n" +
				"trusted_content_hash = \"" + EARLIER_CONTENT_HASH + "\"\n\n" +
				"[hooks.state.\"other@market:hooks/hooks.json:session_start:0:0\"]\n" +
				"trusted_hash = \"sha256:" + "b".repeat(64) + "\"\ntrusted_content_hash = \"sha256:" + "c".repeat(64) +
				"\"\n\n" +
				"[hooks.state.\"" + SUBAGENT_START + "\"]\ntrusted_hash = \"" + SUBAGENT_START_HASH + "\"\n" +
				"trusted_content_hash = \"" + EARLIER_CONTENT_HASH + "\"\n\n" +
				"[hooks.state.\"" + POST_TOOL_USE + "\"]\ntrusted_hash = \"" + POST_TOOL_USE_HASH + "\"\n" +
				"trusted_content_hash = \"" + EARLIER_CONTENT_HASH + "\"\n";
			Files.writeString(configuration, original);
			List<ObjectNode> listed = List.of(
				AppServerProgram.hook(POST_TOOL_USE, "plugin", "cat@cat", hooksFile,
					POST_TOOL_USE_HASH + "/" + CONTENT_HASH, "modified"),
				AppServerProgram.hook(SESSION_START, "plugin", "cat@cat", hooksFile,
					SESSION_START_HASH + "/" + CONTENT_HASH, "modified"),
				AppServerProgram.hook(SUBAGENT_START, "plugin", "cat@cat", hooksFile,
					SUBAGENT_START_HASH + "/" + CONTENT_HASH, "modified"));

			PluginInstall.InstalledEntries entries = installEntries(home, installer,
				() -> AppServerProgram.listing(listed));

			requireThat(Files.readString(configuration), "configuration").
				isEqualTo(original.replace(EARLIER_CONTENT_HASH, CONTENT_HASH));
			requireThat(entries.notices().stream().anyMatch(notice -> notice.contains(configuration.toString()) &&
				notice.contains("the hash that Codex lists") && notice.contains(" 3 ")), "reported").isTrue();
		}
	}

	/**
	 * Install records Codex's content hash only for a hook of CAT's plugin, in the version folder being installed,
	 * whose declaration hash is the one that CAT trusted. It leaves another plugin's hook, a project hook, a hook of
	 * another version folder and a hook whose declaration changed as they are, and says that Codex may ask the user
	 * to review CAT's hooks.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void recordsOnlyCatsInstalledHooks() throws IOException
	{
		// Requirement: R-I21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);
			Path hooksFile = installer.getParent().getParent().resolve("hooks/hooks.json");
			Path otherVersion = plugin(home, LATER_VERSION, CAT_HOOKS).getParent().getParent().
				resolve("hooks/hooks.json");
			Path configuration = home.resolve("config.toml");
			String other = "other@market:hooks/hooks.json:session_start:0:0";
			String otherHash = "sha256:" + "b".repeat(64);
			String original = "model = \"gpt\"\n\n" + trusted(other, otherHash) + "\n" +
				trusted(SESSION_START, SESSION_START_HASH) + "\n" + trusted(POST_TOOL_USE, POST_TOOL_USE_HASH) + "\n" +
				trusted(SUBAGENT_START, SUBAGENT_START_HASH);
			Files.writeString(configuration, original);
			List<ObjectNode> listed = List.of(
				AppServerProgram.hook(other, "plugin", "other@market",
					home.resolve("plugins/cache/market/other/1.0/hooks/hooks.json"), otherHash + "/" + CONTENT_HASH,
					"modified"),
				AppServerProgram.hook(SESSION_START, "project", null, home.resolveSibling("project/.codex/hooks.json"),
					SESSION_START_HASH + "/" + CONTENT_HASH, "untrusted"),
				AppServerProgram.hook(SUBAGENT_START, "plugin", "cat@cat", otherVersion,
					SUBAGENT_START_HASH + "/" + CONTENT_HASH, "modified"),
				AppServerProgram.hook(POST_TOOL_USE, "plugin", "cat@cat", hooksFile,
					"sha256:" + "e".repeat(64) + "/" + CONTENT_HASH, "modified"));

			PluginInstall.InstalledEntries entries = installEntries(home, installer,
				() -> AppServerProgram.listing(listed));

			requireThat(Files.readString(configuration), "configuration").isEqualTo(original);
			requireThat(entries.notices().toString(), "notices").contains("Codex may ask you to review");
		}
	}

	/**
	 * An install succeeds when Codex cannot list its hooks, and says that Codex may ask the user to review CAT's hooks.
	 *
	 * @param name      the case's name
	 * @param appServer starts the process of Codex's app-server
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test(dataProvider = "unanswered")
	public void installSucceedsWhenCodexDoesNotList(String name, Supplier<Process> appServer) throws IOException
	{
		// Requirement: R-I21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);
			Path configuration = home.resolve("config.toml");
			Files.writeString(configuration, "model = \"gpt\"\n");

			PluginInstall.InstalledEntries entries = installEntries(home, installer, appServer);

			requireThat(Files.readString(configuration), name).contains(SESSION_START_HASH).
				doesNotContain("trusted_content_hash");
			requireThat(entries.notices().toString(), "notices").contains("Codex may ask you to review");
		}
	}

	/**
	 * A Codex that keeps no content hash lists each of CAT's hooks with its declaration hash alone. Install writes
	 * nothing and succeeds, and its message names the program that it ran, by its path and the version that its
	 * {@code --version} prints, says that this Codex runs CAT's hooks by their declaration hash alone, and warns that a
	 * newer Codex may ask the user to review them.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void namesCodexThatListsNoContentHash() throws IOException
	{
		// Requirement: R-I23
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);
			Path hooksFile = installer.getParent().getParent().resolve("hooks/hooks.json");
			Path configuration = home.resolve("config.toml");
			String original = "model = \"gpt\"\n\n" +
				"[hooks.state.\"" + SESSION_START + "\"]\ntrusted_hash = \"" + SESSION_START_HASH + "\"\n" +
				"trusted_content_hash = \"" + EARLIER_CONTENT_HASH + "\"\n\n" +
				trusted(POST_TOOL_USE, POST_TOOL_USE_HASH) + "\n" + trusted(SUBAGENT_START, SUBAGENT_START_HASH);
			Files.writeString(configuration, original);
			List<ObjectNode> listed = List.of(
				AppServerProgram.hook(POST_TOOL_USE, "plugin", "cat@cat", hooksFile, POST_TOOL_USE_HASH, "trusted"),
				AppServerProgram.hook(SESSION_START, "plugin", "cat@cat", hooksFile, SESSION_START_HASH, "trusted"),
				AppServerProgram.hook(SUBAGENT_START, "plugin", "cat@cat", hooksFile, SUBAGENT_START_HASH, "trusted"));
			HarnessCli cli = CodexProgram.cli(home, () -> AppServerProgram.listing(listed));

			PluginInstall.InstalledEntries entries = installEntries(home, installer, cli);

			requireThat(Files.readString(configuration), "configuration").isEqualTo(original);
			requireThat(entries.notices().toString(), "notices").
				contains(cli.program().toString()).
				contains(CodexProgram.versionAnswer().output().strip()).
				contains("declaration hash alone").
				contains("may ask you to review").
				doesNotContain("did not list");
		}
	}

	/**
	 * Supplies app-servers that do not list the hooks.
	 *
	 * @return each case's name and its app-server
	 */
	@DataProvider
	public Object[][] unanswered()
	{
		Supplier<Process> exited = AppServerProgram::exited;
		Supplier<Process> refusing = () -> AppServerProgram.refusing("hooks/list is unavailable");
		return new Object[][]
			{
				{"exited", exited},
				{"refusing", refusing}
			};
	}

	/**
	 * Uninstalling removes the content hash that install recorded together with the declaration hash that it
	 * recorded in the same table, and leaves the file as it was.
	 *
	 * @throws IOException if a fixture cannot be written or read
	 */
	@Test
	public void uninstallRemovesContentHashWithTrust() throws IOException
	{
		// Requirement: R-I21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory(testRoot(), "home-"));
			Path installer = plugin(home, VERSION, CAT_HOOKS);
			Path hooksFile = installer.getParent().getParent().resolve("hooks/hooks.json");
			Path configuration = home.resolve("config.toml");
			String original = "model = \"gpt\"\n";
			Files.writeString(configuration, original);
			List<ObjectNode> listed = List.of(
				AppServerProgram.hook(SESSION_START, "plugin", "cat@cat", hooksFile,
					SESSION_START_HASH + "/" + CONTENT_HASH, "trusted"),
				AppServerProgram.hook(POST_TOOL_USE, "plugin", "cat@cat", hooksFile,
					POST_TOOL_USE_HASH + "/" + CONTENT_HASH, "trusted"),
				AppServerProgram.hook(SUBAGENT_START, "plugin", "cat@cat", hooksFile,
					SUBAGENT_START_HASH + "/" + CONTENT_HASH, "trusted"));
			PluginInstall.InstalledEntries entries = installEntries(home, installer,
				() -> AppServerProgram.listing(listed));
			String installed = Files.readString(configuration);
			requireThat(installed.split("trusted_content_hash = \"" + CONTENT_HASH, -1).length - 1, "contentHashes").
				isEqualTo(3);

			provider().removePermissions(home, entries.recorded(), replacer());

			requireThat(Files.readString(configuration), "removed").isEqualTo(original);
		}
	}

	/**
	 * Installs the entries of a plugin version as install, update and repair do after registration.
	 *
	 * @param home      the Codex home
	 * @param installer the plugin version's installer launcher
	 * @param appServer starts the process of Codex's app-server
	 * @return the installed entries
	 * @throws IOException if an entry cannot be installed
	 */
	private static PluginInstall.InstalledEntries installEntries(Path home, Path installer,
		Supplier<Process> appServer) throws IOException
	{
		return installEntries(home, installer, CodexProgram.cli(home, appServer));
	}

	/**
	 * Installs the entries of a plugin version as install, update and repair do after registration, running Codex's
	 * program through a runner.
	 *
	 * @param home      the Codex home
	 * @param installer the plugin version's installer launcher
	 * @param cli       runs Codex's program
	 * @return the installed entries
	 * @throws IOException if an entry cannot be installed
	 */
	private static PluginInstall.InstalledEntries installEntries(Path home, Path installer, HarnessCli cli)
		throws IOException
	{
		HarnessIntegration provider = provider();
		Path locks = home.resolveSibling("user/.cat/locks");
		return new PluginInstall(provider, cli, replacer(), NO_WAIT).
			installEntries(permissionEnvironment(home), home, installer, provider.installation(home), List.of(),
				locks, locks.resolveSibling("sessions"), List.of(), false);
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
