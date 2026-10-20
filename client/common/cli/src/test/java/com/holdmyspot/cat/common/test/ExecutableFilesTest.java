package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
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
		try (Process process = Yielding.executableFiles().run(ProcessFixture.builder(ProcessFixture.Mode.EXIT)))
		{
			requireThat(process.waitFor(), "exitCode").isEqualTo(0);
		}
	}

	/**
	 * Verifies a text-file-busy failure pauses before each retry, until a start succeeds.
	 *
	 * @throws IOException if the simulated process start fails
	 */
	@Test public void retriesTextFileBusyStart() throws IOException
	{
		AtomicInteger attempts = new AtomicInteger();
		AtomicInteger pauses = new AtomicInteger();
		ExecutableFiles executables = new ExecutableFiles(pauses::incrementAndGet, new ManualClock(Instant.EPOCH));

		ExecutableFilesSecrets.access().run(executables, () ->
		{
			if (attempts.getAndIncrement() < 3)
				throw new IOException("error=26, Text file busy");
			return null;
		});

		requireThat(attempts.get(), "attempts").isEqualTo(4);
		requireThat(pauses.get(), "pauses").isEqualTo(3);
	}

	/**
	 * Verifies a persistent text-file-busy failure stops once the one-second limit passes.
	 *
	 * @throws IOException if the simulated process start fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*ETXTBSY.*")
	public void givesUpAtRetryLimit() throws IOException
	{
		AtomicInteger attempts = new AtomicInteger();
		AtomicInteger pauses = new AtomicInteger();
		ManualClock clock = new ManualClock(Instant.EPOCH);
		ExecutableFiles executables = new ExecutableFiles(() ->
		{
			pauses.incrementAndGet();
			clock.advance(Duration.ofMillis(250));
		}, clock);
		try
		{
			ExecutableFilesSecrets.access().run(executables, () ->
			{
				attempts.incrementAndGet();
				throw new IOException("ETXTBSY");
			});
		}
		finally
		{
			requireThat(attempts.get(), "attempts").isEqualTo(5);
			requireThat(pauses.get(), "pauses").isEqualTo(4);
		}
	}

	/**
	 * Verifies a failed pause stops further start attempts, keeping the start failure as a suppressed exception.
	 *
	 * @throws IOException if the simulated pause fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "interrupted")
	public void pauseFailureStopsRetries() throws IOException
	{
		AtomicInteger attempts = new AtomicInteger();
		ExecutableFiles executables = new ExecutableFiles(() ->
		{
			throw new IOException("interrupted");
		}, new ManualClock(Instant.EPOCH));
		try
		{
			ExecutableFilesSecrets.access().run(executables, () ->
			{
				attempts.incrementAndGet();
				throw new IOException("ETXTBSY");
			});
		}
		catch (IOException failure)
		{
			requireThat(failure.getSuppressed()[0].getMessage(), "suppressed").isEqualTo("ETXTBSY");
			throw failure;
		}
		finally
		{
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
		ExecutableFiles executables = new ExecutableFiles(() ->
		{
			throw new AssertionError("A non-transient failure must not pause");
		}, new ManualClock(Instant.EPOCH));
		try
		{
			ExecutableFilesSecrets.access().run(executables, () ->
			{
				attempts.incrementAndGet();
				throw new IOException("Permission denied");
			});
		}
		finally
		{
			requireThat(attempts.get(), "attempts").isEqualTo(1);
		}
	}
}
