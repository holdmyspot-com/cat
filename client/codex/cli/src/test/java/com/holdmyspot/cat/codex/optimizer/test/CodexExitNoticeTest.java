package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.optimizer.CodexExitNotice;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * Tests the exit notice command without starting a host or model.
 */
public final class CodexExitNoticeTest
{
	/**
	 * Creates the test.
	 */
	public CodexExitNoticeTest()
	{
	}

	/**
	 * The exit note is plain text, addresses exactly the owning thread, and queues once.
	 *
	 * @throws Exception if sending fails
	 */
	@Test
	public void queuesOnePlainNoteToOwnThread() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			String executable = executable(cleanup);
			String thread = "2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b";
			AtomicInteger calls = new AtomicInteger();
			CodexExitNotice.send(executable, thread, "campaign-1", Map.of(), (arguments, environment) ->
			{
				requireThat(arguments, "arguments").isEqualTo(List.of(executable, "queue", "--thread", thread,
					"--message", "Campaign campaign-1 background run ended. " +
						"Call prompt-optimizer status campaign-1 for the result."));
				calls.incrementAndGet();
				return 0;
			});
			requireThat(calls.get(), "calls").isEqualTo(1);
		}
	}

	/**
	 * Queue refusal is returned to the driver for durable reporting, without interpreting the campaign outcome.
	 *
	 * @throws Exception if sending fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*status 7.*")
	public void queueFailureIsExplicit() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexExitNotice.send(executable(cleanup),
				"2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b", "campaign-1", Map.of(), (_, _) -> 7);
		}
	}

	/**
	 * An untrusted thread identifier cannot add queue arguments or shell syntax.
	 *
	 * @throws Exception if sending fails
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*invalid.*")
	public void rejectsUntrustedThreadBeforeLaunching() throws Exception
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			CodexExitNotice.send(executable(cleanup), "--thread other; injected", "campaign-1",
				Map.of(), (_, _) ->
				{
					throw new AssertionError("Unvalidated input reached the queue launcher");
				});
		}
	}
	/**
	 * Supplies a fixture executable path without consulting the host's working directory or installed programs.
	 *
	 * @param cleanup removes the fixture
	 * @return the absolute fixture path
	 * @throws IOException if a fixture directory cannot be created
	 */
	private static String executable(TemporaryDirectories cleanup) throws IOException
	{
		Path directory = cleanup.add(Files.createTempDirectory("exit-notice"));
		return directory.resolve("codex").toString();
	}
}
