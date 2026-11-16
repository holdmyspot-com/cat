package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.Test;

/**
 * Runs the launcher double's lifecycle contract through {@link ExecutableFiles} with {@link LifecycleProcess} as
 * the actual child. JVM startup and termination are the boundary under test; no runner, harness or model starts.
 */
public final class ExecutableProcessContractTest extends ProcessEofContract
{
	/**
	 * Creates the actual executable adapter checks.
	 */
	public ExecutableProcessContractTest()
	{
	}

	/**
	 * A live JVM child remains live after a zero-duration wait until fixture control completes it.
	 *
	 * @throws IOException if child setup or cleanup fails
	 * @throws InterruptedException if process completion is interrupted
	 */
	@Test
	public void childWaitDoesNotCompleteProcess() throws IOException, InterruptedException
	{
		ProcessCompletionContract.waitDoesNotCompleteRunningProcess(Started::completion);
	}

	/**
	 * A JVM child publishes its selected normal exit status to all process observations.
	 *
	 * @throws IOException if child setup or cleanup fails
	 * @throws InterruptedException if process completion is interrupted
	 */
	@Test
	public void childCompletionPublishesStatus() throws IOException, InterruptedException
	{
		ProcessCompletionContract.explicitCompletionPublishesStatus(Started::completion);
	}

	/**
	 * Forcibly stopping the owned JVM child reaches a terminal process state.
	 *
	 * @throws IOException if child setup or cleanup fails
	 * @throws InterruptedException if process completion is interrupted
	 */
	@Test
	public void childForceStopCompletesProcess() throws IOException, InterruptedException
	{
		ProcessCompletionContract.forceStopCompletesOnlyRoot(Started::completion);
	}

	@Override
	protected EofFixture start(EofScenario scenario) throws IOException
	{
		return new Started(scenario);
	}

	/**
	 * Waits for the child's readiness announcement with a failure bound.
	 *
	 * @param process the child whose own code must have started
	 * @throws IOException if the child fails to announce readiness
	 */
	private static void awaitReady(Process process) throws IOException
	{
		try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor())
		{
			Future<?> ready = readers.submit(() ->
			{
				readReadyLine(process.getInputStream());
				return null;
			});
			try
			{
				ready.get(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e)
			{
				InterruptedIOException failure = new InterruptedIOException("Interrupted awaiting the fixture child");
				failure.initCause(e);
				terminateAfterSetupFailure(process, failure);
				Thread.currentThread().interrupt();
				throw failure;
			}
			catch (ExecutionException | TimeoutException e)
			{
				IOException failure = new IOException("The fixture child did not become ready", e);
				terminateAfterSetupFailure(process, failure);
				throw failure;
			}
		}
	}

	/**
	 * Reads the exact readiness line without closing stdout, which later EOF checks still need.
	 *
	 * @param output the child's stdout
	 * @throws IOException if stdout ends early or differs from the expected line
	 */
	private static void readReadyLine(InputStream output) throws IOException
	{
		byte[] ready = (LifecycleProcess.READY + "\n").getBytes(StandardCharsets.UTF_8);
		for (byte expected : ready)
		{
			int actual = output.read();
			if (actual != (expected & 0xFF))
				throw new IOException("The fixture child did not announce readiness");
		}
	}

	/**
	 * Stops a child whose setup did not complete and adds any cleanup failure to the setup failure.
	 *
	 * @param process the child to stop
	 * @param failure the setup failure receiving cleanup details
	 */
	private static void terminateAfterSetupFailure(Process process, IOException failure)
	{
		process.destroyForcibly();
		try
		{
			if (!process.waitFor(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS))
				failure.addSuppressed(new IOException("The fixture child did not stop after setup failed"));
		}
		catch (InterruptedException e)
		{
			failure.addSuppressed(e);
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Owns the child and the directory used by its JVM.
	 */
	private static final class Started implements EofFixture, ProcessCompletionContract.Fixture
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Process process;
		private final boolean completionChild;

		/**
		 * Starts the child controlled by a selected normal exit status.
		 *
		 * @return owned completion fixture
		 * @throws IOException if the child cannot start or announce readiness
		 */
		private static Started completion() throws IOException
		{
			return new Started(null, true);
		}

		/**
		 * Starts a ready child and owns its resources, including cleanup when setup fails.
		 *
		 * @param scenario the EOF lifecycle to run in the child
		 * @throws IOException if the child cannot start or announce readiness
		 */
		private Started(EofScenario scenario) throws IOException
		{
			this(scenario, false);
		}

		/**
		 * Starts the requested lifecycle child and owns its temporary directory.
		 *
		 * @param scenario EOF lifecycle, or null for status control
		 * @param completionChild whether input selects the normal exit status
		 * @throws IOException if the child cannot start or announce readiness
		 */
		private Started(EofScenario scenario, boolean completionChild) throws IOException
		{
			this.completionChild = completionChild;
			try (TestJvmScope scope = new TestJvmScope())
			{
				Path directory = cleanup.add(Files.createTempDirectory(scope.temporaryDirectory(), "process-contract-"));
				String executable = "java";
				if (Platform.current().isWindows())
					executable = "java.exe";
				Path java = Path.of(System.getProperty("java.home"), "bin", executable);
				String childScenario;
				if (completionChild)
					childScenario = "COMPLETION_STATUS";
				else
					childScenario = scenario.name();
				ProcessBuilder builder = new ProcessBuilder(java.toString(), "-XX:+PerfDisableSharedMem",
					"-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory.resolve("absent-home"),
					"--module-path", System.getProperty("jdk.module.path"), "--module",
					"com.holdmyspot.cat.common.cli.test/" + LifecycleProcess.class.getName(), childScenario);
				builder.directory(directory.toFile());
				ExecutableFiles executables = new ExecutableFiles(() ->
				{
					throw new AssertionError("The fixture start must not retry");
				}, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
				this.process = executables.run(builder);
				try
				{
					awaitReady(process);
				}
				catch (IOException | RuntimeException e)
				{
					try (process)
					{
						process.destroyForcibly();
						throw e;
					}
				}
			}
			catch (IOException | RuntimeException e)
			{
				try (cleanup)
				{
					throw e;
				}
			}
		}

		@Override
		public Process process()
		{
			return process;
		}

		@Override
		public void complete(int status) throws IOException
		{
			if (!completionChild)
				throw new IllegalStateException("Status control requires the completion child");
			process.getOutputStream().write((Integer.toString(status) + "\n").getBytes(StandardCharsets.UTF_8));
			process.getOutputStream().close();
		}

		@Override
		public boolean observerAlive()
		{
			return process.isAlive();
		}

		@Override
		public boolean otherActorAlive()
		{
			return false;
		}

		@Override
		public void complete() throws IOException
		{
			process.getOutputStream().close();
		}

		@Override
		public void close() throws IOException
		{
			process.destroyForcibly();
			try
			{
				process.waitFor(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
				process.close();
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted stopping the fixture child");
			}
			cleanup.close();
		}
	}
}
