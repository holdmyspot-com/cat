package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.codex.runner.CodexStartupHookComparison;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.port.HookInspection;
import com.holdmyspot.cat.common.optimizer.port.HookPair;
import com.holdmyspot.cat.common.runner.AbstractHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.HeartbeatTicker;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import com.holdmyspot.cat.common.test.ExitedProcess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.SkipException;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies which of the user's Codex hook approvals reach the trial copy of a plugin that the project builds. CAT's
 * own plugin, built from the project, inherits the user's approvals of its installed {@code cat@cat} hooks by
 * declaration hash, although its plugin files differ from the installed copy's; any other project-built plugin
 * inherits none.
 * <p>
 * The native {@code hooks/list} records follow codex-cli 0.171.0's response, retained in the goal session's
 * {@code temp/native-frozen-trust-lQss2j/same-original-bare-hash.jsonl}: a hook's {@code currentHash} is its
 * declaration hash and its plugin's artifact hash, and Codex trusts a hook whose configured {@code trusted_hash}
 * equals the declaration hash.
 */
public final class CodexProjectBuiltCatHooksTest
{
	private static final String CAT_REPOSITORY = "https://github.com/holdmyspot-com/cat";
	private static final String CAT_ID = "cat@cat";
	private static final String INSTALLED_ARTIFACT = "sha256:" + "d".repeat(64);
	private static final String BUILT_ARTIFACT = "sha256:" + "e".repeat(64);
	private static final List<NativeHook> HOOKS = List.of(
		new NativeHook("sessionStart", "session_start", "session-start.sh", "sha256:" + "a".repeat(64)),
		new NativeHook("postToolUse", "post_tool_use", "post-tool-use.sh", "sha256:" + "b".repeat(64)),
		new NativeHook("subagentStart", "subagent_start", "subagent-start.sh", "sha256:" + "c".repeat(64)));

	/**
	 * A CAT trial copy built from the project receives the user's approvals of the installed CAT's hooks, so trials
	 * run its SessionStart hook and the startup comparison checks it, and no hook is reported as unapproved.
	 *
	 * @throws IOException          if the fixture cannot be written or the comparison fails
	 * @throws InterruptedException if the comparison is interrupted
	 */
	@Test
	public void carriesUserCatApprovalsToProjectBuiltCat() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture(CAT_REPOSITORY))
		{
			HookInspection inspection = fixture.resolve();

			requireThat(inspection.unapproved(), "unapproved").isEqualTo(Optional.of(List.of()));
			requireThat(inspection.pairs().size(), "comparedStartupHooks").isEqualTo(1);
			HookPair pair = inspection.pairs().getFirst();
			requireThat(pair.trial().environment().get("PLUGIN_ROOT"), "trialPluginRoot").
				isEqualTo(fixture.trialRoot.toString());
			requireThat(pair.production().environment().get("PLUGIN_ROOT"), "productionPluginRoot").
				isEqualTo(fixture.hostRoot.toString());
			JsonNode state = fixture.trialHookState();
			for (NativeHook hook : HOOKS)
			{
				requireThat(state.path(hook.key(CAT_ID)).path("trusted_hash").asString(""), hook.event()).
					isEqualTo(hook.declaration());
			}
		}
	}

	/**
	 * A plugin that the project builds and that is not CAT inherits no approval, even under CAT's name and with the
	 * same hook declarations, so every one of its hooks is reported as unapproved.
	 *
	 * @throws IOException          if the fixture cannot be written or the comparison fails
	 * @throws InterruptedException if the comparison is interrupted
	 */
	@Test
	public void leavesOtherBuiltPluginUnapproved() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture("https://example.com/other/cat"))
		{
			HookInspection inspection = fixture.resolve();

			requireThat(inspection.unapproved(), "unapproved").isEqualTo(Optional.of(List.of(
				"cat SessionStart `\"$PLUGIN_ROOT/hooks/session-start.sh\"`",
				"cat PostToolUse `\"$PLUGIN_ROOT/hooks/post-tool-use.sh\"`",
				"cat SubagentStart `\"$PLUGIN_ROOT/hooks/subagent-start.sh\"`")));
			requireThat(inspection.pairs(), "comparedStartupHooks").isEmpty();
			requireThat(fixture.trialHookState().isEmpty(), "trialApprovals").isTrue();
		}
	}

	/**
	 * One of CAT's hooks as Codex lists it.
	 *
	 * @param event       the native event name
	 * @param keyEvent    the event's name in the hook key
	 * @param script      the script below the plugin's {@code hooks} directory
	 * @param declaration the hook's declaration hash
	 */
	private record NativeHook(String event, String keyEvent, String script, String declaration)
	{
		/**
		 * Returns the hook's native key.
		 *
		 * @param pluginId the plugin and marketplace identity
		 * @return the key
		 */
		String key(String pluginId)
		{
			return pluginId + ":hooks/hooks.json:" + keyEvent + ":0:0";
		}
	}

	/**
	 * A user's Codex home with CAT installed and its hooks approved, a project-built plugin and its comparison home,
	 * with every native launch answered in memory.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final JsonMapper json = JsonMapper.builder().build();
		private final Path root;
		private final Path host;
		private final Path hostRoot;
		private final Path project;
		private final Path production;
		private final Path trialHome;
		private final Path trial;
		private final Path trialRoot;
		private final Path built;
		private final Path bin;
		private final AbstractHarnessRunnerScope scope;

		/**
		 * Creates the fixture.
		 *
		 * @param repository the {@code repository} in the project-built plugin's manifest
		 * @throws IOException if the fixture cannot be written
		 */
		private Fixture(String repository) throws IOException
		{
			if (!Platform.current().isLinux())
				throw new SkipException("Codex hook inspection uses the Linux filesystem view");
			try (TestJvmScope build = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(build.temporaryDirectory(), "built-cat-hooks-")).
					toRealPath();
			}
			host = Files.createDirectories(root.resolve("host"));
			Files.writeString(host.resolve("installation_id"), "00000000-0000-0000-0000-000000000000");
			Files.createDirectory(host.resolve(".tmp"));
			StringBuilder approvals = new StringBuilder(512).append("[plugins.\"cat@cat\"]\nenabled = true\n");
			for (NativeHook hook : HOOKS)
			{
				approvals.append("\n[hooks.state.\"").append(hook.key(CAT_ID)).append("\"]\ntrusted_hash = \"").
					append(hook.declaration()).append("\"\n");
			}
			Files.writeString(host.resolve("config.toml"), approvals);
			hostRoot = plugin(host.resolve("plugins/cache/cat/cat/1.0+installed"), CAT_REPOSITORY);
			project = Files.createDirectory(root.resolve("project"));
			production = Files.createDirectory(root.resolve("production"));
			trialHome = Files.createDirectories(root.resolve("comparison/home"));
			trial = Files.createDirectory(root.resolve("comparison/project"));
			// Codex registers and caches a plugin when it installs it; the fixture's installation commands do nothing
			Files.writeString(trialHome.resolve("config.toml"), "[plugins.\"cat@cat\"]\nenabled = true\n");
			trialRoot = plugin(trialHome.resolve("plugins/cache/cat/cat/1.0+built"), repository);
			built = plugin(root.resolve("built"), repository);
			bin = Files.createDirectory(root.resolve("bin"));
			for (String program : List.of("bwrap", "codex"))
			{
				Path file = Files.createFile(bin.resolve(program));
				requireThat(file.toFile().setExecutable(true), "setExecutable").isTrue();
			}
			Clock clock = new ManualClock(Instant.EPOCH);
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(() -> {}, clock,
				builder ->
				{
					List<String> command = builder.command();
					if (command.contains("app-server"))
						return appServer(Path.of(command.get(command.indexOf("CODEX_HOME") + 1)));
					return new ExitedProcess(builder, 0, "", "");
				});
			scope = new AbstractHarnessRunnerScope(new TestJvmScope(root, executables))
			{
				@Override
				public Clock clock()
				{
					return clock;
				}

				@Override
				protected HeartbeatTicker heartbeatTicker()
				{
					throw new AssertionError("Startup-hook inspection never starts a heartbeat");
				}
			};
		}

		/**
		 * Writes a plugin whose hooks are CAT's.
		 *
		 * @param directory  the plugin directory
		 * @param repository the manifest's {@code repository}
		 * @return the plugin directory's real path
		 * @throws IOException if the plugin cannot be written
		 */
		private static Path plugin(Path directory, String repository) throws IOException
		{
			Files.createDirectories(directory.resolve(".codex-plugin"));
			Files.writeString(directory.resolve(".codex-plugin/plugin.json"),
				"{\"name\":\"cat\",\"version\":\"1.0\",\"repository\":\"" + repository + "\"}");
			Files.createDirectory(directory.resolve("hooks"));
			Files.writeString(directory.resolve("hooks/hooks.json"), "{\"hooks\":{}}");
			return directory.toRealPath();
		}

		/**
		 * Starts an in-memory app-server for one Codex home.
		 *
		 * @param home the {@code CODEX_HOME} of the launch
		 * @return the app-server
		 */
		private FakeHarnessCli appServer(Path home)
		{
			AtomicReference<FakeHarnessCli> server = new AtomicReference<>();
			server.set(new FakeHarnessCli(line ->
			{
				JsonNode request = json.readTree(line);
				long id = request.path("id").asLong(0);
				switch (request.path("method").asString(""))
				{
					case "initialize" -> server.get().emit("{\"id\":" + id + ",\"result\":{\"userAgent\":" +
						"\"codex/0.171.0\",\"codexHome\":" + json.writeValueAsString(home.toString()) +
						",\"platformFamily\":\"unix\",\"platformOs\":\"linux\"}}");
					case "hooks/list" -> server.get().emit("{\"id\":" + id + ",\"result\":" +
						hooks(home, request.path("params").path("cwds").path(0).asString()) + "}");
					default ->
					{
					}
				}
			}));
			return server.get();
		}

		/**
		 * Lists CAT's hooks as Codex lists them in one home: the installed copy and the project build have the same
		 * declarations and different artifacts, and a hook is trusted when the home's configuration approves its
		 * declaration.
		 *
		 * @param home the Codex home
		 * @param cwd  the inspected directory
		 * @return the {@code hooks/list} result
		 */
		private String hooks(Path home, String cwd)
		{
			Path pluginRoot;
			String artifact;
			if (home.equals(host))
			{
				pluginRoot = hostRoot;
				artifact = INSTALLED_ARTIFACT;
			}
			else
			{
				pluginRoot = trialRoot;
				artifact = BUILT_ARTIFACT;
			}
			JsonNode state = hookState(home);
			ObjectNode directory = json.createObjectNode().put("cwd", cwd);
			ArrayNode hooks = directory.putArray("hooks");
			for (int order = 0; order < HOOKS.size(); ++order)
			{
				NativeHook hook = HOOKS.get(order);
				String trust = "untrusted";
				if (hook.declaration().equals(state.path(hook.key(CAT_ID)).path("trusted_hash").asString("")))
					trust = "trusted";
				hooks.addObject().put("key", hook.key(CAT_ID)).put("eventName", hook.event()).
					put("handlerType", "command").put("command", "\"$PLUGIN_ROOT/hooks/" + hook.script() + "\"").
					put("async", false).putNull("matcher").put("timeoutSec", 600).
					put("sourcePath", pluginRoot.resolve("hooks/hooks.json").toString()).put("source", "plugin").
					put("pluginId", CAT_ID).put("displayOrder", order).put("enabled", true).put("isManaged", false).
					put("currentHash", hook.declaration() + "/" + artifact).put("trustStatus", trust);
			}
			directory.putArray("warnings");
			directory.putArray("errors");
			ObjectNode result = json.createObjectNode();
			result.putArray("data").add(directory);
			return result.toString();
		}

		/**
		 * Reads the hook approvals of one Codex home.
		 *
		 * @param home the Codex home
		 * @return the {@code hooks.state} table, or a missing node if there is none
		 */
		private static JsonNode hookState(Path home)
		{
			Path configuration = home.resolve("config.toml");
			try
			{
				return TomlMapper.builder().build().readTree(Files.readString(configuration)).path("hooks").
					path("state");
			}
			catch (IOException e)
			{
				throw new AssertionError("Cannot read " + configuration, e);
			}
		}

		/**
		 * Returns the comparison home's hook approvals.
		 *
		 * @return the {@code hooks.state} table, or a missing node if there is none
		 */
		private JsonNode trialHookState()
		{
			return hookState(trialHome);
		}

		/**
		 * Runs the startup-hook comparison of the project-built plugin.
		 *
		 * @return the comparison's hook inspection
		 * @throws IOException          if the comparison fails
		 * @throws InterruptedException if the comparison is interrupted
		 */
		private HookInspection resolve() throws IOException, InterruptedException
		{
			CodexStartupHookComparison.Context context = new CodexStartupHookComparison.Context(project, host,
				"codex", Map.of("PATH", bin.toString()), ParentPolicy.UNSANDBOXED, scope);
			return CodexStartupHookComparison.resolve(context, List.of(new TrialPluginInput(built, Optional.empty())),
				production, trial, trialHome, "compare");
		}

		@Override
		public void close() throws IOException
		{
			try (TemporaryDirectories _ = cleanup)
			{
				scope.close();
			}
		}
	}
}
