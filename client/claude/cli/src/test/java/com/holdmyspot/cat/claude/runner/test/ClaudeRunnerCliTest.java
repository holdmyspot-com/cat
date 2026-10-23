package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.runner.ClaudeRunnerCli;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeRunnerCli}.
 */
public final class ClaudeRunnerCliTest
{
	private static final Path TEMP = Path.of(System.getProperty("cat.session.temp")).toAbsolutePath().normalize();

	/**
	 * Creates the test.
	 */
	public ClaudeRunnerCliTest()
	{
	}

	/**
	 * Verifies that a bridge given {@code --capacity-wait} gives up when no capacity frees within the wait, before it
	 * starts a session.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void capacityWaitExpires() throws IOException
	{
		// Requirement: R-I9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "runner-cli-"));
			ManualClock clock = new ManualClock(Instant.parse("2026-10-23T12:00:00Z"));
			AtomicInteger pauses = new AtomicInteger();
			Pause second = () ->
			{
				if (pauses.incrementAndGet() > 100)
					throw new AssertionError("The capacity wait did not end");
				clock.advance(Duration.ofSeconds(1));
			};
			HarnessCapacity capacity = HarnessCapacity.of(root.resolve("locks"), 1, second, clock);
			String[] args = {"--artifact", root.resolve("artifact").toString(), "--project",
				root.resolve("project").toString(), "--run-root", root.resolve("runs").toString(), "--capacity-wait",
				"5"};
			try (HarnessCapacity.Permit _ = capacity.acquire())
			{
				ClaudeRunnerCli.serve(args, new StringReader(""), new StringWriter(), Map.of(), _ -> capacity);
				throw new AssertionError("The bridge did not give up waiting for capacity");
			}
			catch (HarnessCapacityWaitExpiredException _)
			{
				requireThat(pauses.get(), "pauses").isEqualTo(5);
			}
		}
	}
}
