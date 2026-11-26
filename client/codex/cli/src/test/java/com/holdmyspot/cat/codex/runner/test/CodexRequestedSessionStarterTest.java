package com.holdmyspot.cat.codex.runner.test;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexRequestServerCli;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.FakeProcessLauncher;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionStarterContract;
import com.holdmyspot.cat.common.runner.login.CodexLoginService;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Runs the {@link RequestedSessionStarter} contract against the starter of the Codex request server, which starts
 * the {@code codex-runner} launcher of its plugin with its {@code serve} command for a Codex session. A stand-in for
 * the operating system records the runner process that the starter starts, and no runner starts.
 */
public final class CodexRequestedSessionStarterTest extends RequestedSessionStarterContract
{
	/**
	 * Creates the test.
	 */
	public CodexRequestedSessionStarterTest()
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
		return new LauncherStarts(Map.of(), Map.of());
	}

	/**
	 * Verifies that the runner of a requested session, and the runner of a session that the requested session requests
	 * in turn, receive the address of the request server's login service and the key with which a runner opens a
	 * session's login, so that each asks the same service, while the harness markers stay out of their environments.
	 *
	 * @throws IOException if the starter fails
	 */
	@Test
	public void runnersReachLoginService() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "requested-runner-"));
			Map<String, String> service = Map.of(CodexLoginService.SOCKET_VARIABLE,
				directory.resolve("login/s").toString(), CodexLoginService.KEY_VARIABLE, "fixture-service-key");
			Map<String, String> requestedRunner;
			try (LauncherStarts starts = new LauncherStarts(Map.of("PATH", "", "CODEX_THREAD_ID", "thread-1"),
				service);
				Process requested = starts.starter().start(HARNESS_ID, List.of("--model", "model-a"), directory))
			{
				requestedRunner = starts.environments().getFirst();
				requested.destroy();
			}
			// A requested session's runner starts the runners of the sessions that its session requests with its own
			// environment, without the harness markers, as the request server's starter starts them
			Map<String, String> nestedRunner;
			try (LauncherStarts nestedStarts = new LauncherStarts(requestedRunner, Map.of());
				Process nested = nestedStarts.starter().start(HARNESS_ID, List.of("--model", "model-a"), directory))
			{
				nestedRunner = nestedStarts.environments().getFirst();
				nested.destroy();
			}
			for (Map<String, String> environment : List.of(requestedRunner, nestedRunner))
			{
				for (Map.Entry<String, String> variable : service.entrySet())
				{
					requireThat(environment.get(variable.getKey()), variable.getKey()).
						isEqualTo(variable.getValue());
				}
				requireThat(environment.keySet(), "variables").doesNotContain("CODEX_THREAD_ID");
			}
		}
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
		 * @param environment    the environment that the request server holds
		 * @param loginVariables the variables that reach the server's login service
		 * @throws IOException if the scope's temporary folder cannot be created
		 */
		LauncherStarts(Map<String, String> environment, Map<String, String> loginVariables) throws IOException
		{
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(() ->
			{
			}, new ManualClock(Instant.EPOCH), processes);
			this.jvm = new TestJvmScope(build.temporaryDirectory(), executables);
			Path artifact = Path.of("/plugins/cat/1.0").toAbsolutePath();
			this.starter = CodexRequestServerCli.starter(jvm, artifact, environment, loginVariables);
			String launcherName = "codex-runner";
			if (Platform.current().isWindows())
				launcherName += ".cmd";
			this.launcher = artifact.resolve("bin").resolve(launcherName);
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
				int options = 1;
				if (command.size() > 1 && command.getFirst().equals(launcher.toString()) &&
					command.get(1).equals("serve"))
				{
					harness = HARNESS_ID;
					options = 2;
				}
				return new StartedRunner(harness, List.copyOf(command.subList(options, command.size())),
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
