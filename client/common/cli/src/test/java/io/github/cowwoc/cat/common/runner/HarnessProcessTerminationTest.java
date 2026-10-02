package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.ProcessFixture;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


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
		try (Process process = ProcessFixture.builder(ProcessFixture.Mode.TREE).start())
		{
			List<ProcessHandle> children = List.of();
			try
			{
				ProcessFixture.awaitStarted(process);
				children = process.toHandle().descendants().toList();
				HarnessProcessTermination.terminate(process, "test harness");
				List<ProcessHandle> descendants = process.toHandle().descendants().toList();
				requireThat(process.isAlive(), "process.isAlive").isFalse();
				requireThat(descendants.stream().anyMatch(ProcessHandle::isAlive), "liveDescendants").isFalse();
			}
			finally
			{
				children.forEach(ProcessHandle::destroyForcibly);
				if (process.isAlive())
					process.destroyForcibly();
			}
		}
	}

	/**
	 * Verifies shutdown cleanup does not leave a runner child alive after the owning JVM starts to exit.
	 *
	 * @throws IOException if process execution fails
	 *
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void immediatelyTerminatesVisibleChildren() throws IOException, InterruptedException
	{
		try (Process process = ProcessFixture.builder(ProcessFixture.Mode.TREE).start())
		{
			List<ProcessHandle> children = List.of();
			try
			{
				ProcessFixture.awaitStarted(process);
				children = process.toHandle().descendants().toList();
				HarnessProcessTermination.terminateImmediately(process);
				requireThat(process.waitFor(5, TimeUnit.SECONDS), "processExit").isTrue();
				requireThat(process.toHandle().descendants().anyMatch(ProcessHandle::isAlive), "liveDescendants").
				isFalse();
			}
			finally
			{
				children.forEach(ProcessHandle::destroyForcibly);
				if (process.isAlive())
					process.destroyForcibly();
			}
		}
	}
}
