package com.holdmyspot.cat.common.test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks EOF transitions shared by a scripted process and an actual fixture child.
 */
public abstract class ProcessEofContract extends ProcessLifecycleContract
{
	/**
	 * Creates the shared EOF checks.
	 */
	protected ProcessEofContract()
	{
	}

	@Override
	protected final EofFixture start() throws IOException
	{
		return start(EofScenario.INPUT_EOF_EXIT);
	}

	/**
	 * Starts a process with the selected input and output EOF behavior.
	 *
	 * @param scenario the EOF lifecycle to exercise
	 * @return the process and its fixture controls
	 * @throws IOException if the fixture cannot start
	 */
	protected abstract EofFixture start(EofScenario scenario) throws IOException;

	/**
	 * Closing stdin may let a process finish or may leave it alive.
	 *
	 * @throws IOException            if the fixture cannot start or close
	 * @throws InterruptedException   if waiting for the child is interrupted
	 * @throws ExecutionException     if reading stdout fails
	 * @throws TimeoutException       if the child or stdout does not complete within the failure bound
	 */
	@Test
	public void inputEofCompletesChild() throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (EofFixture fixture = start(EofScenario.INPUT_EOF_EXIT))
		{
			ProcessHandle handle = fixture.process().toHandle();
			fixture.process().getOutputStream().close();

			requireThat(fixture.process().waitFor(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS), "ended").isTrue();
			requireThat(fixture.process().waitFor(), "exitStatus").isEqualTo(0);
			requireThat(fixture.process().exitValue(), "exitValue").isEqualTo(0);
			requireThat(fixture.process().isAlive(), "processAlive").isFalse();
			requireThat(handle.isAlive(), "handleAlive").isFalse();
			requireThat(readStdout(fixture.process()), "stdout").isEqualTo(-1);
		}
	}

	/**
	 * Closing stdin can leave a child alive until the caller terminates it.
	 *
	 * @throws IOException          if the fixture cannot start or close
	 * @throws InterruptedException if waiting for the child is interrupted
	 */
	@Test
	public void inputEofCanLeaveChildAlive() throws IOException, InterruptedException
	{
		try (EofFixture fixture = start(EofScenario.INPUT_EOF_KEEP_ALIVE))
		{
			ProcessHandle handle = fixture.process().toHandle();
			fixture.process().getOutputStream().close();

			requireThat(fixture.process().waitFor(0, TimeUnit.NANOSECONDS), "ended").isFalse();
			requireThat(fixture.process().isAlive(), "processAlive").isTrue();
			requireThat(handle.isAlive(), "handleAlive").isTrue();
			fixture.process().destroyForcibly();
			requireThat(fixture.process().waitFor(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS), "ended").isTrue();
			requireThat(fixture.process().isAlive(), "processAlive").isFalse();
			requireThat(handle.isAlive(), "handleAlive").isFalse();
		}
	}

	/**
	 * stdout can reach EOF while the child remains alive, then explicit completion ends it.
	 *
	 * @throws IOException            if the fixture cannot start, complete or close
	 * @throws InterruptedException   if waiting for the child is interrupted
	 * @throws ExecutionException     if reading stdout fails
	 * @throws TimeoutException       if stdout or the child does not complete within the failure bound
	 */
	@Test
	public void outputEofPrecedesProcessExit() throws IOException, InterruptedException, ExecutionException,
		TimeoutException
	{
		try (EofFixture fixture = start(EofScenario.OUTPUT_EOF_BEFORE_EXIT))
		{
			Future<Process> exit = fixture.process().onExit();
			ProcessHandle handle = fixture.process().toHandle();
			requireThat(readStdout(fixture.process()), "stdout").isEqualTo(-1);
			requireThat(fixture.process().waitFor(0, TimeUnit.NANOSECONDS), "exited").isFalse();
			requireThat(exit.isDone(), "exitFutureComplete").isFalse();
			requireThat(fixture.process().isAlive(), "processAlive").isTrue();
			requireThat(handle.isAlive(), "handleAlive").isTrue();
			assertExitStatusUnavailable(fixture.process());

			fixture.complete();
			requireThat(fixture.process().waitFor(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS), "ended").isTrue();
			requireThat(exit.get(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS) == fixture.process(),
				"exitProcess").isTrue();
			requireThat(fixture.process().waitFor(), "exitStatus").isEqualTo(0);
			requireThat(fixture.process().exitValue(), "exitValue").isEqualTo(0);
			requireThat(fixture.process().isAlive(), "processAlive").isFalse();
			requireThat(handle.isAlive(), "handleAlive").isFalse();
		}
	}

	/**
	 * Reads one stdout byte with a failure bound and stops the process if that read cannot finish.
	 *
	 * @param process the child whose stdout is observed
	 * @return the byte value or -1 at EOF
	 * @throws IOException            if cleanup after a failed read cannot close stdout
	 * @throws InterruptedException   if the read or cleanup wait is interrupted
	 * @throws ExecutionException     if reading stdout fails
	 * @throws TimeoutException       if stdout does not produce a byte or EOF within the failure bound
	 */
	private static int readStdout(Process process) throws IOException, InterruptedException, ExecutionException,
		TimeoutException
	{
		try (ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor())
		{
			Future<Integer> read = readers.submit(() -> process.getInputStream().read());
			try
			{
				return read.get(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException failure)
			{
				stopReaderProcess(process, failure);
				Thread.currentThread().interrupt();
				throw failure;
			}
			catch (TimeoutException failure)
			{
				stopReaderProcess(process, failure);
				throw failure;
			}
		}
	}

	/**
	 * Stops the process and unblocks the reader after its bounded read fails.
	 *
	 * @param process the child whose stdout reader must be released
	 * @param failure the read failure receiving cleanup details
	 */
	private static void stopReaderProcess(Process process, Exception failure)
	{
		process.destroyForcibly();
		try
		{
			process.waitFor(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS);
			process.getInputStream().close();
		}
		catch (InterruptedException cleanupFailure)
		{
			failure.addSuppressed(cleanupFailure);
			Thread.currentThread().interrupt();
		}
		catch (IOException cleanupFailure)
		{
			failure.addSuppressed(cleanupFailure);
		}
	}

	/**
	 * Confirms that a live child does not expose its terminal status.
	 *
	 * @param process the live child
	 */
	private static void assertExitStatusUnavailable(Process process)
	{
		try
		{
			process.exitValue();
		}
		catch (IllegalThreadStateException _)
		{
			return;
		}
		throw new AssertionError("A running process exposed its exit status");
	}

	/**
	 * Selects one of the independent stdin and stdout EOF lifecycles.
	 */
	public enum EofScenario
	{
		INPUT_EOF_EXIT,
		INPUT_EOF_KEEP_ALIVE,
		OUTPUT_EOF_BEFORE_EXIT
	}

	/**
	 * A process fixture with explicit completion control.
	 */
	public interface EofFixture extends Fixture
	{
		/**
		 * Completes the scenario after output EOF has already been observed.
		 *
		 * @throws IOException if the completion signal cannot be sent
		 */
		void complete() throws IOException;
	}
}
