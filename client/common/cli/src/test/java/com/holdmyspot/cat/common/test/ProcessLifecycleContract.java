package com.holdmyspot.cat.common.test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks the lifecycle that CAT consumes from a launched process and its handle. The same cases run against the
 * launcher double and a fixture child started through the executable-file adapter.
 */
public abstract class ProcessLifecycleContract
{
	/**
	 * Failure bound for a fixture transition, rather than a delay used to cause that transition.
	 */
	protected static final Duration EXIT_WAIT = Duration.ofSeconds(10);

	/**
	 * Creates the shared checks.
	 */
	protected ProcessLifecycleContract()
	{
	}

	/**
	 * Starts a ready leaf process that remains alive until explicitly terminated.
	 *
	 * @return the process and its cleanup owner
	 * @throws IOException if the fixture cannot start
	 */
	protected abstract Fixture start() throws IOException;

	/**
	 * A running process has no exit status and an immediate wait does not finish it.
	 * <p>
	 * Through the executable adapter the case starts a real child JVM and waits for its readiness line, which
	 * takes over a second. The test keeps it because the real process is the boundary the contract checks.
	 *
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test(expectedExceptions = IllegalThreadStateException.class)
	public void startsWithUnavailableExitStatus() throws IOException, InterruptedException
	{
		try (Fixture fixture = start())
		{
			requireThat(fixture.process().isAlive(), "processAlive").isTrue();
			requireThat(fixture.process().toHandle().isAlive(), "handleAlive").isTrue();
			requireThat(fixture.process().waitFor(0, TimeUnit.NANOSECONDS), "ended").isFalse();
			fixture.process().exitValue();
		}
	}

	/**
	 * Destroying a process makes its retained handle report completion too.
	 *
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test
	public void processDestroyCompletesHandle() throws IOException, InterruptedException
	{
		try (Fixture fixture = start())
		{
			ProcessHandle handle = fixture.process().toHandle();
			fixture.process().destroy();
			requireCompleted(fixture, handle);
		}
	}

	/**
	 * Forced process termination completes both views of the same process.
	 * <p>
	 * Through the executable adapter the case starts a real child JVM and waits for its readiness line, which
	 * takes over a second. The test keeps it because the real process is the boundary the contract checks.
	 *
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test
	public void processForceDestroyCompletesHandle() throws IOException, InterruptedException
	{
		try (Fixture fixture = start())
		{
			ProcessHandle handle = fixture.process().toHandle();
			requireThat(fixture.process().destroyForcibly(), "destroyed").
				isReferenceEqualTo(fixture.process(), "process");
			requireCompleted(fixture, handle);
		}
	}

	/**
	 * Destroying the retained handle completes the process's waits and exit status.
	 * <p>
	 * Through the executable adapter the case starts a real child JVM and waits for its readiness line, which
	 * takes over a second. The test keeps it because the real process is the boundary the contract checks.
	 *
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test
	public void handleDestroyCompletesProcess() throws IOException, InterruptedException
	{
		try (Fixture fixture = start())
		{
			ProcessHandle handle = fixture.process().toHandle();
			handle.destroy();
			requireCompleted(fixture, handle);
		}
	}

	/**
	 * Forcibly destroying the retained handle completes the process's waits and exit status.
	 *
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if the wait is interrupted
	 */
	@Test
	public void handleForceDestroyCompletesProcess() throws IOException, InterruptedException
	{
		try (Fixture fixture = start())
		{
			ProcessHandle handle = fixture.process().toHandle();
			handle.destroyForcibly();
			requireCompleted(fixture, handle);
		}
	}

	/**
	 * Observing exit leaves the process running until termination, then returns that completed process.
	 *
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException if exit observation fails
	 * @throws TimeoutException if exit observation does not finish
	 */
	@Test
	public void exitFutureCompletesWithProcess() throws IOException, InterruptedException, ExecutionException,
		TimeoutException
	{
		try (Fixture fixture = start())
		{
			ProcessHandle handle = fixture.process().toHandle();
			CompletableFuture<Process> exited = fixture.process().onExit();
			requireThat(exited.isDone(), "exited").isFalse();
			requireThat(fixture.process().isAlive(), "processAlive").isTrue();
			fixture.process().destroy();
			requireThat(exited.get(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS), "exitedProcess").
				isReferenceEqualTo(fixture.process(), "process");
			requireCompleted(fixture, handle);
		}
	}

	/**
	 * Checks the two views after the explicit termination request.
	 *
	 * @param fixture the owner of the process receiving termination or exit observation
	 * @param handle  its handle captured before termination
	 * @throws InterruptedException if the wait is interrupted
	 */
	private static void requireCompleted(Fixture fixture, ProcessHandle handle) throws InterruptedException
	{
		requireThat(fixture.process().waitFor(EXIT_WAIT.toMillis(), TimeUnit.MILLISECONDS), "ended").isTrue();
		requireThat(fixture.process().isAlive(), "processAlive").isFalse();
		requireThat(handle.isAlive(), "handleAlive").isFalse();
		requireThat(fixture.process().waitFor(), "exitStatus").isEqualTo(fixture.process().exitValue());
	}

	/**
	 * Owns a ready process and removes its resources after termination.
	 */
	public interface Fixture extends AutoCloseable
	{
		/**
		 * Returns the same ready process under test on every call.
		 *
		 * @return the process
		 */
		Process process();

		/**
		 * Terminates the process and releases its fixture resources.
		 *
		 * @throws IOException if fixture cleanup fails
		 */
		@Override
		void close() throws IOException;
	}
}
