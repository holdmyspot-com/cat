package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies {@link ExecutableFiles} execution and retry behavior.
 */
public final class ExecutableFilesTest
{
	/**
	 * Creates the test fixture.
	 */
	public ExecutableFilesTest()
	{
	}

	/**
	 * Verifies a configured executable process is started.
	 *
	 * @throws IOException if the process cannot be started
	 * @throws InterruptedException if the process wait is interrupted
	 */
	@Test public void runsConfiguredExecutable() throws IOException, InterruptedException
	{
		try (Process process = ExecutableFiles.run(ProcessFixture.builder(ProcessFixture.Mode.EXIT)))
		{
			requireThat(process.waitFor(), "exitCode").isEqualTo(0);
		}
	}

	/**
	 * Verifies a text-file-busy failure requests exponential backoff before a successful fourth start.
	 *
	 * @throws IOException if the simulated process start fails
	 */
	@Test public void retriesTextFileBusyStart() throws IOException
	{
		AtomicInteger attempts = new AtomicInteger();
		List<Duration> delays = new ArrayList<>();

		ExecutableFilesSecrets.access().run(() ->
		{
			if (attempts.getAndIncrement() < 3)
				throw new IOException("error=26, Text file busy");
			return null;
		}, delays::add);

		requireThat(attempts.get(), "attempts").isEqualTo(4);
		requireThat(delays, "delays").isEqualTo(List.of(
			Duration.ofMillis(125), Duration.ofMillis(250), Duration.ofMillis(500)));
	}

	/**
	 * Verifies a persistent text-file-busy failure stops after four attempts and three backoff requests.
	 *
	 * @throws IOException if the simulated process start fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*ETXTBSY.*")
	public void givesUpAfterTextFileBusyRetries() throws IOException
	{
		AtomicInteger attempts = new AtomicInteger();
		List<Duration> delays = new ArrayList<>();
		try
		{
			ExecutableFilesSecrets.access().run(() ->
			{
				attempts.incrementAndGet();
				throw new IOException("ETXTBSY");
			}, delays::add);
		}
		finally
		{
			requireThat(attempts.get(), "attempts").isEqualTo(4);
			requireThat(delays, "delays").isEqualTo(List.of(
				Duration.ofMillis(125), Duration.ofMillis(250), Duration.ofMillis(500)));
		}
	}

	/**
	 * Verifies an interrupted retry wait preserves interruption and stops further start attempts.
	 *
	 * @throws IOException if the simulated retry wait is interrupted
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Interrupted while retrying a text-file-busy process start")
	public void interruptedWaitStopsRetries() throws IOException
	{
		AtomicInteger attempts = new AtomicInteger();
		InterruptedException interruption = new InterruptedException("retry interrupted");
		try
		{
			ExecutableFilesSecrets.access().run(() ->
			{
				attempts.incrementAndGet();
				throw new IOException("ETXTBSY");
			}, delay ->
			{
				requireThat(delay, "delay").isEqualTo(Duration.ofMillis(125));
				throw interruption;
			});
		}
		catch (IOException failure)
		{
			requireThat(failure.getCause(), "cause").isEqualTo(interruption);
			throw failure;
		}
		finally
		{
			boolean interrupted = Thread.interrupted();
			requireThat(interrupted, "interrupted").isTrue();
			requireThat(attempts.get(), "attempts").isEqualTo(1);
		}
	}

	/**
	 * Verifies a non-transient process-start failure is not retried.
	 *
	 * @throws IOException if the simulated process start fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Permission denied.*")
	public void doesNotRetryOtherStartFailure() throws IOException
	{
		AtomicInteger attempts = new AtomicInteger();
		try
		{
			ExecutableFilesSecrets.access().run(() ->
			{
				attempts.incrementAndGet();
				throw new IOException("Permission denied");
			}, delay ->
			{
				throw new AssertionError("A non-transient failure must not request a retry wait");
			});
		}
		finally
		{
			requireThat(attempts.get(), "attempts").isEqualTo(1);
		}
	}
}
