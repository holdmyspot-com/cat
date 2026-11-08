package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.CodeLoadingVariables;
import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies {@link ExecutableFiles} execution and retry behavior.
 */
public final class ExecutableFilesTest
{
	/**
	 * A clock that the tests whose start never reaches its limit give the instance.
	 */
	private static final Clock FIXED = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

	/**
	 * Creates the test fixture.
	 */
	public ExecutableFilesTest()
	{
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
		ExecutableFiles executables = new ExecutableFiles(pauses::incrementAndGet, FIXED);

		ExecutableFilesSecrets.getExecutableFilesAccess().run(executables, () ->
		{
			if (attempts.getAndIncrement() < 3)
				throw new IOException("error=26, Text file busy");
			return null;
		});

		requireThat(attempts.get(), "attempts").isEqualTo(4);
		requireThat(pauses.get(), "pauses").isEqualTo(3);
	}

	/**
	 * Verifies that only a text-file-busy failure is tried again, and only until the one-second limit.
	 */
	@Test
	public void retriesOnlyBusyFileBeforeLimit()
	{
		Instant start = Instant.EPOCH;
		Instant deadline = start.plus(Duration.ofSeconds(1));

		requireThat(ExecutableFiles.retries(new IOException("error=26, Text file busy"), start, deadline), "text").
			isTrue();
		requireThat(ExecutableFiles.retries(new IOException("ETXTBSY"), deadline.minusNanos(1), deadline), "code").
			isTrue();
		requireThat(ExecutableFiles.retries(new IOException("ETXTBSY"), deadline, deadline), "atLimit").isFalse();
		requireThat(ExecutableFiles.retries(new IOException("Permission denied"), start, deadline), "other").
			isFalse();
		requireThat(ExecutableFiles.retries(new IOException(), start, deadline), "noMessage").isFalse();
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
			ExecutableFilesSecrets.getExecutableFilesAccess().run(executables, () ->
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
		}, FIXED);
		try
		{
			ExecutableFilesSecrets.getExecutableFilesAccess().run(executables, () ->
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
		}, FIXED);
		try
		{
			ExecutableFilesSecrets.getExecutableFilesAccess().run(executables, () ->
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

	/**
	 * Verifies that a process that CAT starts gets none of the variables that make a program load code that its caller
	 * chose, whatever its builder holds, and keeps every other variable.
	 *
	 * @throws IOException if the simulated process start fails
	 */
	@Test
	public void startsProcessWithoutCodeLoadingVariables() throws IOException
	{
		List<Map<String, String>> started = new ArrayList<>();
		ExecutableFiles executables = ExecutableFilesSecrets.getExecutableFilesAccess().create(() ->
		{
			throw new AssertionError("A start that succeeds does not pause");
		}, FIXED, builder ->
		{
			started.add(Map.copyOf(builder.environment()));
			return null;
		});
		ProcessBuilder builder = new ProcessBuilder("program");
		Map<String, String> environment = builder.environment();
		environment.clear();
		environment.put("PATH", "programs");
		environment.put("HARNESS_SETTING", "kept");
		for (String name : CodeLoadingVariables.NAMES)
			environment.put(name, "injected");
		environment.put("DYLD_INSERT_LIBRARIES", "injected");
		environment.put("DYLD_FRAMEWORK_PATH", "injected");

		executables.run(builder);

		requireThat(started, "started").isEqualTo(List.of(Map.of("PATH", "programs", "HARNESS_SETTING", "kept")));
	}
}
