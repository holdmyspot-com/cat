package io.github.cowwoc.cat.common;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Verifies executable-file execution behavior.
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
		try (Process process = ExecutableFiles.run(new ProcessBuilder("true")))
		{
			requireThat(process.waitFor(), "exitCode").isEqualTo(0);
		}
	}

	/**
	 * Verifies a text-file-busy failure is retried before a process starts.
	 *
	 * @throws IOException if the simulated process start fails
	 */
	@Test public void retriesTextFileBusyStart() throws IOException
	{
		AtomicInteger attempts = new AtomicInteger();

		ExecutableFiles.run(() ->
		{
			if (attempts.getAndIncrement() < 3)
				throw new IOException("error=26, Text file busy");
			return null;
		});

		requireThat(attempts.get(), "attempts").isEqualTo(4);
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
			ExecutableFiles.run(() ->
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
