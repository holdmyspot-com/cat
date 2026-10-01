package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeRunnerCli} against a real Claude process.
 * <p>
 * The tests run only in the {@code native} group, with {@code -Dcat.native.harness=claude}. They take longer than two
 * seconds because they start Claude and then wait out a fixed interval to show that a runner is blocked. Both runners
 * resolve the shared capacity directory from the {@code HOME} that they inherit, as the installed runner does, so a
 * real session that already holds the first slot makes the test fail rather than skip.
 */
public final class ClaudeRunnerCliNativeTest
{
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(2);
	private static final Duration BLOCKED_INTERVAL = Duration.ofSeconds(10);

	/**
	 * Creates the test fixture.
	 */
	public ClaudeRunnerCliNativeTest()
	{
	}

	/**
	 * Verifies that two runner processes limited to one concurrent session run one after the other.
	 *
	 * @throws IOException if the filesystem operation or a runner process fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 */
	@Test(groups = "native")
	public void serializesRunnersAtMaximumOfOne() throws IOException, InterruptedException
	{
		// Requirement: R-E8
		if (!"claude".equals(System.getProperty("cat.native.harness")))
			throw new SkipException("Run with -Pnative -Dcat.native.harness=claude");
		String workTemp = System.getProperty("cat.work.temp");
		if (workTemp == null || workTemp.isBlank())
			throw new IllegalStateException("The build must pass the CAT work temp root as cat.work.temp");
		Path workTempRoot = Path.of(workTemp);
		Files.createDirectories(workTempRoot);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path fixture = cleanup.add(Files.createTempDirectory(workTempRoot, "native-capacity-"));
			Path artifact = createArtifact(fixture.resolve("artifact"));
			Path project = Files.createDirectories(fixture.resolve("project"));
			Files.writeString(project.resolve("README.md"), "fixture\n", UTF_8);
			List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"--module-path", System.getProperty("jdk.module.path"),
				"--module", ClaudeRunnerCli.class.getModule().getName() + "/" + ClaudeRunnerCli.class.getName(),
				"--artifact", artifact.toString(), "--project", project.toString(),
				"--run-root", fixture.resolve("runs").toString(), "--max-concurrency", "1");

			try (RunnerProcess first = RunnerProcess.start(command, fixture.resolve("first.err")))
			{
				requireThat(first.nextEvent(STARTUP_TIMEOUT), "firstEvent").contains("\"started\"");
				try (RunnerProcess second = RunnerProcess.start(command, fixture.resolve("second.err")))
				{
					requireThat(second.nextEvent(BLOCKED_INTERVAL), "secondEventWhileFirstHoldsCapacity").isNull();

					first.complete();
					requireThat(first.nextEvent(STARTUP_TIMEOUT), "firstCompletion").contains("\"completed\"");
					requireThat(first.waitForExit(), "firstExitCode").isEqualTo(0);

					requireThat(second.nextEvent(STARTUP_TIMEOUT), "secondEvent").contains("\"started\"");
					second.complete();
					requireThat(second.nextEvent(STARTUP_TIMEOUT), "secondCompletion").contains("\"completed\"");
					requireThat(second.waitForExit(), "secondExitCode").isEqualTo(0);
				}
			}
		}
	}

	/**
	 * Creates the smallest directory that the runner accepts as a Claude artifact.
	 *
	 * @param artifact the directory to create
	 * @return {@code artifact}
	 * @throws IOException if the filesystem operation fails
	 */
	private static Path createArtifact(Path artifact) throws IOException
	{
		Files.createDirectories(artifact.resolve(".claude-plugin"));
		Files.createDirectories(artifact.resolve("client/bin"));
		Files.writeString(artifact.resolve(".claude-plugin/plugin.json"), "{}\n", UTF_8);
		Files.writeString(artifact.resolve("client/VERSION"), PluginInfo.CAT_VERSION + "\n", UTF_8);
		for (String launcher : List.of("plugin-info", "session-start"))
		{
			Path file = artifact.resolve("client/bin").resolve(launcher);
			Files.writeString(file, "#!/bin/sh\nexit 0\n", UTF_8);
			Files.setPosixFilePermissions(file, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
		}
		return artifact;
	}

	/**
	 * A runner process whose standard output is read on a background thread.
	 */
	private static final class RunnerProcess implements AutoCloseable
	{
		private final Process process;
		private final Writer input;
		private final BlockingQueue<String> events = new LinkedBlockingQueue<>();

		/**
		 * Wraps a started runner.
		 *
		 * @param process the runner process
		 */
		private RunnerProcess(Process process)
		{
			this.process = process;
			this.input = process.outputWriter(UTF_8);
			Thread.ofVirtual().start(() ->
			{
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8)))
				{
					String line = reader.readLine();
					while (line != null)
					{
						events.add(line);
						line = reader.readLine();
					}
				}
				catch (IOException _)
				{
					// The process ended. Callers observe that through the missing events and the exit code.
				}
			});
		}

		/**
		 * Starts a runner.
		 *
		 * @param command the command line
		 * @param errorFile where the runner's standard error is written
		 * @return the running process
		 * @throws IOException if the process cannot start
		 */
		private static RunnerProcess start(List<String> command, Path errorFile) throws IOException
		{
			return new RunnerProcess(new ProcessBuilder(command).redirectError(errorFile.toFile()).start());
		}

		/**
		 * Waits for the runner's next output line.
		 *
		 * @param timeout how long to wait
		 * @return the line, or {@code null} if the runner wrote nothing within {@code timeout}
		 * @throws InterruptedException if the wait is interrupted
		 */
		private String nextEvent(Duration timeout) throws InterruptedException
		{
			return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		/**
		 * Tells the runner that no more turns follow.
		 *
		 * @throws IOException if the runner's input is closed
		 */
		private void complete() throws IOException
		{
			input.write("{\"type\":\"complete\"}\n");
			input.flush();
		}

		/**
		 * Waits for the runner to exit.
		 *
		 * @return the exit code
		 * @throws InterruptedException if the wait is interrupted
		 */
		private int waitForExit() throws InterruptedException
		{
			return process.waitFor();
		}

		@Override
		public void close()
		{
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		}
	}
}
