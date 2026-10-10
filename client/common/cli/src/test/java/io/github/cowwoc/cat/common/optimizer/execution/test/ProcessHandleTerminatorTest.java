package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.execution.ProcessHandleTerminator;
import io.github.cowwoc.cat.common.test.ProcessFixture;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Covers the termination of a recorded process, matched by its identifier and start time, with real child processes.
 */
public final class ProcessHandleTerminatorTest
{
	/**
	 * How long a test waits for a process to exit.
	 */
	private static final long WAIT_SECONDS = 10;

	/**
	 * Creates the test.
	 */
	public ProcessHandleTerminatorTest()
	{
	}

	/**
	 * Starts a process that runs until it is terminated.
	 *
	 * @param directory the process's working directory
	 * @param mode the process's behavior
	 * @return the process
	 * @throws IOException if the process cannot start
	 */
	private static Process start(Path directory, ProcessFixture.Mode mode) throws IOException
	{
		return ProcessFixture.builder(mode).directory(directory.toFile()).start();
	}

	/**
	 * Returns a process's start time.
	 *
	 * @param process the process
	 * @return the start time
	 */
	private static Instant startedAt(ProcessHandle process)
	{
		return process.info().startInstant().orElseThrow();
	}

	/**
	 * Verifies that a process whose identifier matches but whose start time does not, as after the identifier was
	 * reused, is left running.
	 *
	 * @throws IOException if the process cannot start
	 */
	@Test
	public void mismatchedStartTimeIsLeftRunning() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("terminator"));
			try (Process process = start(directory, ProcessFixture.Mode.CHILD))
			{
				try
				{
					ProcessHandle handle = process.toHandle();
					boolean terminated = new ProcessHandleTerminator().terminate(handle.pid(),
						startedAt(handle).minusSeconds(1));
					requireThat(terminated, "terminated").isFalse();
					requireThat(process.isAlive(), "isAlive").isTrue();
				}
				finally
				{
					process.destroyForcibly();
				}
			}
		}
	}

	/**
	 * Verifies that a process whose identifier and start time match exits along with its descendants after termination
	 * is requested.
	 *
	 * @throws IOException if the process cannot start or its output cannot be read
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if waiting for a process fails
	 * @throws TimeoutException if a process does not exit in time
	 */
	@Test
	public void matchingProcessTreeIsTerminated()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("terminator"));
			try (Process process = start(directory, ProcessFixture.Mode.TREE))
			{
				List<ProcessHandle> descendants = List.of();
				try
				{
					// The Java parent reports readiness after its child has started
					try (BufferedReader output = process.inputReader(UTF_8))
					{
						requireThat(output.readLine(), "output").isEqualTo("started");
					}
					ProcessHandle handle = process.toHandle();
					descendants = handle.descendants().toList();
					requireThat(descendants, "descendants").isNotEmpty();

					boolean terminated = new ProcessHandleTerminator().terminate(handle.pid(), startedAt(handle));
					requireThat(terminated, "terminated").isTrue();
					handle.onExit().get(WAIT_SECONDS, TimeUnit.SECONDS);
					for (ProcessHandle descendant : descendants)
						descendant.onExit().get(WAIT_SECONDS, TimeUnit.SECONDS);
				}
				finally
				{
					// A child that outlived the parent is no longer its descendant
					descendants.forEach(ProcessHandle::destroyForcibly);
					process.destroyForcibly();
				}
			}
		}
	}

	/**
	 * Verifies that the driver's own process is never terminated.
	 */
	@Test
	public void currentProcessIsLeftRunning()
	{
		// Requirement: R-E11
		ProcessHandle current = ProcessHandle.current();
		requireThat(new ProcessHandleTerminator().terminate(current.pid(), startedAt(current)), "terminated").
			isFalse();
	}

	/**
	 * Verifies that a process that already exited is reported as not terminated.
	 *
	 * @throws IOException if the process cannot start
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void exitedProcessIsNotTerminated() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("terminator"));
			try (Process process = start(directory, ProcessFixture.Mode.CHILD))
			{
				ProcessHandle handle = process.toHandle();
				Instant startedAt = startedAt(handle);
				process.destroy();
				requireThat(process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS), "exited").isTrue();
				requireThat(new ProcessHandleTerminator().terminate(handle.pid(), startedAt), "terminated").isFalse();
			}
		}
	}
}
