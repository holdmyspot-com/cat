package com.holdmyspot.cat.common.test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Checks that process waits observe completion supplied by the process owner and that all completion views agree.
 */
public final class ProcessCompletionContract
{
	/**
	 * Failure bound for process completion and waiter joining; it never causes a process transition.
	 */
	private static final Duration WAIT = Duration.ofSeconds(5);

	/**
	 * Creates the shared process completion checks.
	 */
	private ProcessCompletionContract()
	{
	}

	/**
	 * A wait remains pending until fixture control explicitly completes the process.
	 *
	 * @param factory creates a fresh running process
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if process completion or the waiter is interrupted
	 */
	public static void waitDoesNotCompleteRunningProcess(FixtureFactory factory) throws IOException,
		InterruptedException
	{
		try (Fixture fixture = factory.open(); Process process = fixture.process())
		{
			try
			{
				requireThat(process.isAlive(), "processStartsLive").isTrue();
				requireExitStatusUnavailable(process);
				requireThat(process.waitFor(0, TimeUnit.NANOSECONDS), "zeroWaitCompletesProcess").isFalse();
				requireThat(process.isAlive(), "zeroWaitKeepsProcessLive").isTrue();
				requireThat(fixture.observerAlive(), "zeroWaitKeepsObservedProcessLive").isTrue();

				CountDownLatch waiterEntered = new CountDownLatch(1);
				FutureTask<Integer> result = new FutureTask<>(process::waitFor);
				Thread waiter = Thread.ofPlatform().start(() ->
				{
					waiterEntered.countDown();
					result.run();
				});
				try
				{
					requireThat(waiterEntered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "waiterStarted").isTrue();
					requireThat(result.isDone(), "waitCompletesBeforeExplicitControl").isFalse();
					fixture.complete(7);
					try
					{
						requireThat(result.get(WAIT.toSeconds(), TimeUnit.SECONDS), "completedWaitStatus").isEqualTo(7);
					}
					catch (ExecutionException | TimeoutException exception)
					{
						throw new AssertionError("The explicitly completed waiter did not return", exception);
					}
				}
				finally
				{
					if (waiter.isAlive())
					{
						process.destroyForcibly();
						waiter.join(WAIT.toMillis());
					}
				}
				requireThat(waiter.isAlive(), "waiterRemainsAfterCompletion").isFalse();
			}
			finally
			{
				process.destroyForcibly();
			}
		}
	}

	/**
	 * Explicit completion publishes one status to waits, exitValue, and the fixture's process observer.
	 *
	 * @param factory creates a fresh running process
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if process completion is interrupted
	 */
	public static void explicitCompletionPublishesStatus(FixtureFactory factory) throws IOException,
		InterruptedException
	{
		try (Fixture fixture = factory.open(); Process process = fixture.process())
		{
			try
			{
				requireThat(process.isAlive(), "processStartsLive").isTrue();
				fixture.complete(7);
				requireThat(process.waitFor(WAIT.toSeconds(), TimeUnit.SECONDS), "timedWaitObservesCompletion").isTrue();
				requireThat(process.waitFor(), "waitStatus").isEqualTo(7);
				requireThat(process.exitValue(), "exitStatus").isEqualTo(7);
				requireThat(process.isAlive(), "processEnds").isFalse();
				requireThat(fixture.observerAlive(), "observerSeesProcessEnded").isFalse();
				requireThat(process.waitFor(), "repeatedWaitStatus").isEqualTo(7);
				requireThat(process.exitValue(), "repeatedExitStatus").isEqualTo(7);
			}
			finally
			{
				process.destroyForcibly();
			}
		}
	}

	/**
	 * Forcible termination completes the root process and preserves the fixture's other actor state.
	 *
	 * @param factory creates a fresh running process
	 * @throws IOException if the fixture cannot start or close
	 * @throws InterruptedException if process completion is interrupted
	 */
	public static void forceStopCompletesOnlyRoot(FixtureFactory factory) throws IOException,
		InterruptedException
	{
		try (Fixture fixture = factory.open(); Process process = fixture.process())
		{
			try
			{
				boolean otherActorAlive = fixture.otherActorAlive();
				requireThat(process.isAlive(), "processStartsLive").isTrue();
				requireThat(process.destroyForcibly(), "forceStopResult").isReferenceEqualTo(process, "process");
				requireThat(process.waitFor(WAIT.toSeconds(), TimeUnit.SECONDS), "forceStopCompletesRoot").isTrue();
				requireThat(process.exitValue(), "forceStopExitStatus").isEqualTo(process.waitFor());
				requireThat(process.isAlive(), "rootAfterForceStop").isFalse();
				requireThat(fixture.observerAlive(), "observerAfterForceStop").isFalse();
				requireThat(fixture.otherActorAlive(), "otherActorAfterForceStop").isEqualTo(otherActorAlive);
			}
			finally
			{
				process.destroyForcibly();
			}
		}
	}

	/**
	 * Requires exitValue to reject a process that is still live.
	 *
	 * @param process process under test
	 */
	private static void requireExitStatusUnavailable(Process process)
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
	 * Opens one fresh process fixture for a shared lifecycle case.
	 */
	@FunctionalInterface
	public interface FixtureFactory
	{
		/**
		 * Creates a running process and its external observer.
		 *
		 * @return owned fixture
		 * @throws IOException if fixture setup fails
		 */
		Fixture open() throws IOException;
	}

	/**
	 * Supplies process completion independently of waits and observes the process outside its Process methods.
	 */
	public interface Fixture extends AutoCloseable
	{
		/**
		 * Returns the process under test.
		 *
		 * @return process under test
		 */
		Process process();

		/**
		 * Completes the process with the supplied status without relying on a wait operation.
		 *
		 * @param status process exit status
		 */
		void complete(int status) throws IOException;

		/**
		 * Indicates whether the fixture's external process observer still considers the root live.
		 *
		 * @return true if the root is externally live
		 */
		boolean observerAlive();

		/**
		 * Indicates whether another actor owned by the fixture is live.
		 *
		 * @return true if another actor is live
		 */
		boolean otherActorAlive();

		/**
		 * Stops and joins the process before releasing fixture resources.
		 *
		 * @throws IOException if fixture cleanup fails
		 */
		@Override
		void close() throws IOException;
	}
}
