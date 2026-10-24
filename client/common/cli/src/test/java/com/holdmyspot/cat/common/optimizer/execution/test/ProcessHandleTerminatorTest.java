package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator;
import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator.ProcessLookup;
import com.holdmyspot.cat.common.secrets.ProcessHandleTerminatorSecrets;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Covers requests to stop recorded processes using in-memory processes that spawn nothing.
 */
public final class ProcessHandleTerminatorTest
{
	/**
	 * Creates the test.
	 */
	public ProcessHandleTerminatorTest()
	{
	}

	/**
	 * Verifies that a reused identifier or an unknown start time leaves the process and its children alone.
	 */
	@Test
	public void unverifiedProcessIsLeftAlone()
	{
		// Requirement: R-E11
		FakeProcessHandle driver = new FakeProcessHandle(1, Instant.EPOCH, true);
		FakeProcessHandle child = new FakeProcessHandle(3, Instant.EPOCH, true);
		for (Instant actualStart : new Instant[]{Instant.EPOCH.plusSeconds(1), null})
		{
			FakeProcessHandle process = new FakeProcessHandle(2, actualStart, true, child);
			ProcessHandleTerminator terminator = terminator(driver, process);

			boolean terminated = terminator.terminate(process.pid(), Instant.EPOCH);

			requireThat(terminated, "terminated").isFalse();
			requireThat(process.stopRequested(), "process.stopRequested").isFalse();
			requireThat(child.stopRequested(), "child.stopRequested").isFalse();
			requireThat(driver.stopRequested(), "driver.stopRequested").isFalse();
		}
	}

	/**
	 * Verifies that the recorded leftover process and the processes it started are asked to stop, leaving others alone.
	 */
	@Test
	public void matchingProcessTreeIsAskedToStop()
	{
		// Requirement: R-E11
		FakeProcessHandle driver = new FakeProcessHandle(1, Instant.EPOCH, true);
		FakeProcessHandle grandchild = new FakeProcessHandle(4, Instant.EPOCH, true);
		FakeProcessHandle child = new FakeProcessHandle(3, Instant.EPOCH, true, grandchild);
		FakeProcessHandle process = new FakeProcessHandle(2, Instant.EPOCH, true, child);
		FakeProcessHandle unrelated = new FakeProcessHandle(5, Instant.EPOCH, true);
		ProcessHandleTerminator terminator = terminator(driver, process, child, grandchild, unrelated);

		boolean terminated = terminator.terminate(process.pid(), Instant.EPOCH);

		requireThat(terminated, "terminated").isTrue();
		requireThat(process.stopRequested(), "process.stopRequested").isTrue();
		requireThat(child.stopRequested(), "child.stopRequested").isTrue();
		requireThat(grandchild.stopRequested(), "grandchild.stopRequested").isTrue();
		requireThat(unrelated.stopRequested(), "unrelated.stopRequested").isFalse();
		requireThat(driver.stopRequested(), "driver.stopRequested").isFalse();
	}

	/**
	 * Verifies that the driver's own process and its children are left alone even when the recorded identity matches.
	 */
	@Test
	public void driverProcessIsLeftAlone()
	{
		// Requirement: R-E11
		FakeProcessHandle child = new FakeProcessHandle(2, Instant.EPOCH, true);
		FakeProcessHandle driver = new FakeProcessHandle(1, Instant.EPOCH, true, child);
		ProcessHandleTerminator terminator = terminator(driver, driver, child);

		boolean terminated = terminator.terminate(driver.pid(), Instant.EPOCH);

		requireThat(terminated, "terminated").isFalse();
		requireThat(driver.stopRequested(), "driver.stopRequested").isFalse();
		requireThat(child.stopRequested(), "child.stopRequested").isFalse();
	}

	/**
	 * Verifies that an exited or missing recorded process reports no termination and leaves other processes alone.
	 */
	@Test
	public void exitedOrMissingProcessIsLeftAlone()
	{
		// Requirement: R-E11
		FakeProcessHandle driver = new FakeProcessHandle(1, Instant.EPOCH, true);
		FakeProcessHandle child = new FakeProcessHandle(3, Instant.EPOCH, true);
		FakeProcessHandle exited = new FakeProcessHandle(2, Instant.EPOCH, false, child);
		ProcessHandleTerminator terminator = terminator(driver, exited, child);

		boolean terminatedExited = terminator.terminate(exited.pid(), Instant.EPOCH);
		boolean terminatedMissing = terminator.terminate(4, Instant.EPOCH);

		requireThat(terminatedExited, "terminatedExited").isFalse();
		requireThat(terminatedMissing, "terminatedMissing").isFalse();
		requireThat(exited.stopRequested(), "exited.stopRequested").isFalse();
		requireThat(child.stopRequested(), "child.stopRequested").isFalse();
		requireThat(driver.stopRequested(), "driver.stopRequested").isFalse();
	}

	/**
	 * Creates a terminator with an in-memory process table.
	 *
	 * @param driver    the driver's own process
	 * @param processes the processes available by identifier
	 * @return the terminator
	 */
	private static ProcessHandleTerminator terminator(FakeProcessHandle driver, FakeProcessHandle... processes)
	{
		return ProcessHandleTerminatorSecrets.getProcessHandleTerminatorAccess().create(new ProcessLookup()
		{
			@Override
			public Optional<ProcessHandle> find(long pid)
			{
				return Stream.of(processes).filter(process -> process.pid() == pid).
					map(ProcessHandle.class::cast).findFirst();
			}

			@Override
			public ProcessHandle current()
			{
				return driver;
			}
		});
	}
}
