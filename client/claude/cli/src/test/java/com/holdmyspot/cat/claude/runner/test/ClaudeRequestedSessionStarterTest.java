package com.holdmyspot.cat.claude.runner.test;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeRequestServerCli;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.FakeProcessLauncher;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionStarterContract;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Runs the {@link RequestedSessionStarter} contract against the starter of the Claude Code request server, which
 * starts the {@code claude-runner} launcher of its plugin for a Claude session. A stand-in for the operating system
 * records the runner process that the starter starts, and no runner starts.
 */
public final class ClaudeRequestedSessionStarterTest extends RequestedSessionStarterContract
{
	/**
	 * Creates the test.
	 */
	public ClaudeRequestedSessionStarterTest()
	{
	}

	@Override
	protected String harness()
	{
		return HARNESS_ID;
	}

	@Override
	protected Starts starts() throws IOException
	{
		return new LauncherStarts(Map.of());
	}

	/**
	 * Verifies that the runner of a requested session receives the caller's Claude authentication variable, so that
	 * the requested session authenticates as its requester does, and not the marker that would make it take itself
	 * for the session that requested it.
	 *
	 * @throws IOException if the starter fails
	 */
	@Test
	public void requestedRunnerReceivesLoginVariable() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope();
			LauncherStarts starts = new LauncherStarts(Map.of("CLAUDE_CODE_OAUTH_TOKEN", "fake-token",
				"CLAUDECODE", "1")))
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "requested-runner-"));

			try (Process runner = starts.starter().start(HARNESS_ID, List.of("--model", "model-a"), directory))
			{
				List<Map<String, String>> environments = starts.environments();
				requireThat(environments.size(), "started").isEqualTo(1);
				requireThat(environments.getFirst().get("CLAUDE_CODE_OAUTH_TOKEN"), "token").isEqualTo("fake-token");
				requireThat(environments.getFirst().keySet(), "variables").doesNotContain("CLAUDECODE");
				runner.destroy();
			}
		}
	}

	/**
	 * Verifies that a requested session, and a session that a requested session requests in turn, use the login of
	 * the user whose session runs the request server: the runner of each receives the user's home directory,
	 * {@code CLAUDE_CONFIG_DIR} and {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}, even an empty one, never a run's own, so
	 * the environment of its Claude process names the login store of the user's own Claude Code in
	 * {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}: the store that the user's {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names,
	 * or else the user's Claude configuration directory; for an empty one, the default store, which on macOS an empty
	 * value selects and elsewhere is {@code .claude} in the user's home directory.
	 *
	 * @param store the user's value of {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}: {@code "named"} for a directory,
	 *              {@code "empty"} for an empty value, or {@code "unset"}
	 * @throws IOException if the starter or an environment fails
	 */
	@Test(dataProvider = "userLoginStores")
	public void requestedSessionsShareUserLogin(String store) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope runner = new TestHarnessRunnerScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "requested-login-")).
				toRealPath();
			Path userHome = Files.createDirectory(root.resolve("user"));
			Path userConfiguration = Files.createDirectory(root.resolve("claude-config"));
			Path runHome = Files.createDirectory(root.resolve("run-home"));
			Path directory = Files.createDirectory(root.resolve("requested"));
			Map<String, String> userEnvironment = new HashMap<>(Map.of("PATH", "", Platform.current().homeVariable(),
				userHome.toString(), "CLAUDE_CONFIG_DIR", userConfiguration.toString(), "CLAUDECODE", "1"));
			String loginStore = switch (store)
			{
				case "named" ->
				{
					String named = Files.createDirectory(root.resolve("login-store")).toString();
					userEnvironment.put("CLAUDE_SECURESTORAGE_CONFIG_DIR", named);
					yield named;
				}
				case "empty" ->
				{
					userEnvironment.put("CLAUDE_SECURESTORAGE_CONFIG_DIR", "");
					if (Platform.current().isLinux() || Platform.current().isWindows())
						yield userHome.resolve(".claude").toString();
					yield "";
				}
				case "unset" -> userConfiguration.toString();
				default -> throw new AssertionError(store);
			};
			try (LauncherStarts starts = new LauncherStarts(userEnvironment);
				Process requested = starts.starter().start(HARNESS_ID, List.of("--model", "model-a"), directory))
			{
				Map<String, String> requestedRunner = starts.environments().getFirst();
				requested.destroy();
				// A requested session's runner starts the runners of the sessions that its session requests with its own
				// environment, without the harness markers, as the request server's starter starts them
				try (LauncherStarts nestedStarts = new LauncherStarts(requestedRunner);
					Process nested = nestedStarts.starter().start(HARNESS_ID, List.of("--model", "model-a"),
						directory))
				{
					Map<String, String> nestedRunner = nestedStarts.environments().getFirst();
					nested.destroy();
					for (Map<String, String> environment : List.of(requestedRunner, nestedRunner))
					{
						Map<String, String> session = ClaudeRunner.processEnvironment(runner, environment,
							HarnessDependencyCaches.NONE, root.resolve("missing-settings.json"), runHome, null);
						requireThat(session.get("CLAUDE_SECURESTORAGE_CONFIG_DIR"), "loginStore").isEqualTo(loginStore);
					}
				}
			}
		}
	}

	/**
	 * Verifies that a requested session, and a session that a requested session requests in turn, receive the class of
	 * the login of the user whose session runs the request server in {@code CAT_HOST_LOGIN}, as the sessions of a
	 * run do: the runner of each receives the user's login store, whatever {@code CAT_HOST_LOGIN} says in the
	 * environment that it receives.
	 *
	 * @throws IOException if the starter or an environment fails
	 */
	@Test
	public void requestedSessionsReceiveTheLoginClass() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope();
			HarnessRunnerScope runner = new TestHarnessRunnerScope())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "requested-class-")).
				toRealPath();
			Path userHome = Files.createDirectory(root.resolve("user"));
			Path store = Files.createDirectory(root.resolve("store"));
			Files.writeString(store.resolve(".credentials.json"),
				"{\"claudeAiOauth\":{\"accessToken\":\"placeholder\"}}");
			Path runHome = Files.createDirectory(root.resolve("run-home"));
			Path directory = Files.createDirectory(root.resolve("requested"));
			Map<String, String> userEnvironment = new HashMap<>(Map.of("PATH", "", Platform.current().homeVariable(),
				userHome.toString(), "CLAUDE_SECURESTORAGE_CONFIG_DIR", store.toString(), "CLAUDECODE", "1",
				"CAT_HOST_LOGIN", "api_key"));
			try (LauncherStarts starts = new LauncherStarts(userEnvironment);
				Process requested = starts.starter().start(HARNESS_ID, List.of("--model", "model-a"), directory))
			{
				Map<String, String> requestedRunner = starts.environments().getFirst();
				requested.destroy();
				try (LauncherStarts nestedStarts = new LauncherStarts(requestedRunner);
					Process nested = nestedStarts.starter().start(HARNESS_ID, List.of("--model", "model-a"),
						directory))
				{
					Map<String, String> nestedRunner = nestedStarts.environments().getFirst();
					nested.destroy();
					for (Map<String, String> environment : List.of(requestedRunner, nestedRunner))
					{
						Map<String, String> session = ClaudeRunner.processEnvironment(runner, environment,
							HarnessDependencyCaches.NONE, root.resolve("missing-settings.json"), runHome, null);
						requireThat(session.get("CAT_HOST_LOGIN"), "hostLogin").isEqualTo("subscription");
					}
				}
			}
		}
	}

	/**
	 * Returns the kinds of the user's {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}.
	 *
	 * @return the kinds
	 */
	@DataProvider
	public static Object[][] userLoginStores()
	{
		return new Object[][]
		{
			{"unset"},
			{"empty"},
			{"named"}
		};
	}

	/**
	 * The request server's starter over a stand-in for the operating system's process start.
	 */
	private static final class LauncherStarts implements Starts
	{
		private final FakeProcessLauncher processes = new FakeProcessLauncher();
		private final TestJvmScope build = new TestJvmScope();
		private final TestJvmScope jvm;
		private final RequestedSessionStarter starter;
		private final Path launcher;

		/**
		 * Creates a starter that has started nothing.
		 *
		 * @param environment the environment that the request server holds
		 * @throws IOException if the scope's temporary folder cannot be created
		 */
		LauncherStarts(Map<String, String> environment) throws IOException
		{
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(() ->
			{
			}, new ManualClock(Instant.EPOCH), processes);
			this.jvm = new TestJvmScope(build.temporaryDirectory(), executables);
			Path artifact = Path.of("/plugins/cat/1.0").toAbsolutePath();
			this.starter = ClaudeRequestServerCli.starter(jvm, artifact, environment);
			this.launcher = artifact.resolve("bin/claude-runner");
		}

		@Override
		public RequestedSessionStarter starter()
		{
			return starter;
		}

		@Override
		public List<StartedRunner> started()
		{
			return processes.started().stream().map(builder ->
			{
				List<String> command = builder.command();
				String harness = "the program " + command.getFirst();
				if (command.getFirst().equals(launcher.toString()))
					harness = HARNESS_ID;
				return new StartedRunner(harness, List.copyOf(command.subList(1, command.size())),
					builder.directory().toPath(), builder.redirectError().file().toPath());
			}).toList();
		}

		/**
		 * Returns the environment of each runner that the starter has started.
		 *
		 * @return the environments, in start order
		 */
		List<Map<String, String>> environments()
		{
			return processes.started().stream().map(builder -> Map.copyOf(builder.environment())).toList();
		}

		@Override
		public void close()
		{
			jvm.close();
			build.close();
		}
	}
}
