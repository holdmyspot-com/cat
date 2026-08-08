package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessProcessTermination}.
 */
public final class HarnessProcessTerminationTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessProcessTerminationTest()
	{
	}

	/**
	 * Verifies runner shutdown terminates an inherited-child process before the direct harness process exits.
	 *
	 * @throws IOException if process execution fails
	 */
	@Test
	public void terminatesVisibleChildren() throws IOException
	{
		if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
			return;
		try (Process process = new ProcessBuilder(List.of("sh", "-c", "sleep 30 & wait")).start())
		{
			try
			{
				waitForDescendant(process);
				HarnessProcessTermination.terminate(process, "test harness");
				List<ProcessHandle> descendants = process.toHandle().descendants().toList();
				requireThat(process.isAlive(), "process.isAlive").isFalse();
				requireThat(descendants.stream().anyMatch(ProcessHandle::isAlive), "liveDescendants").isFalse();
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly();
			}
		}
	}

	/**
	 * Verifies shutdown cleanup does not leave a runner child alive after the owning JVM starts to exit.
	 *
	 * @throws IOException if process execution fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void immediatelyTerminatesVisibleChildren() throws IOException, InterruptedException
	{
		if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
			return;
		try (Process process = new ProcessBuilder(List.of("sh", "-c", "sleep 30 & wait")).start())
		{
			try
			{
				waitForDescendant(process);
				HarnessProcessTermination.terminateImmediately(process);
				requireThat(process.waitFor(5, TimeUnit.SECONDS), "processExit").isTrue();
				requireThat(process.toHandle().descendants().anyMatch(ProcessHandle::isAlive), "liveDescendants").isFalse();
			}
			finally
			{
				if (process.isAlive())
					process.destroyForcibly();
			}
		}
	}

	/**
	 * Waits until the test harness has started its child.
	 *
	 * @param process the direct test harness process
	 * @throws IOException if the child did not start
	 */
	private static void waitForDescendant(Process process) throws IOException
	{
		for (int attempt = 0; attempt < 100; ++attempt)
		{
			if (process.toHandle().descendants().findAny().isPresent())
				return;
			try
			{
				Thread.sleep(10);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for the test harness child", e);
			}
		}
		throw new IOException("Test harness did not start its child");
	}
}
