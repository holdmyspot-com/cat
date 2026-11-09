package com.holdmyspot.cat.claude.runner.test;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeRequestServerCli;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.FakeProcessLauncher;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionStarterContract;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
