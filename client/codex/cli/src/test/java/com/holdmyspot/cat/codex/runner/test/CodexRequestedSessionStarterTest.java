package com.holdmyspot.cat.codex.runner.test;

import static com.holdmyspot.cat.codex.CodexPluginInfo.HARNESS_ID;

import com.holdmyspot.cat.codex.runner.CodexRequestServerCli;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.fakes.test.FakeProcessLauncher;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionStarterContract;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
		return new LauncherStarts();
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
		 * @throws IOException if the scope's temporary folder cannot be created
		 */
		LauncherStarts() throws IOException
		{
			ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(() ->
			{
			}, new ManualClock(Instant.EPOCH), processes);
			this.jvm = new TestJvmScope(build.temporaryDirectory(), executables);
			Path artifact = Path.of("/plugins/cat/1.0").toAbsolutePath();
			this.starter = CodexRequestServerCli.starter(jvm, artifact, Map.of());
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

		@Override
		public void close()
		{
			jvm.close();
			build.close();
		}
	}
}
