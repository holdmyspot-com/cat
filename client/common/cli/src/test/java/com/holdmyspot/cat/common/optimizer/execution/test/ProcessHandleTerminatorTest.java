package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ProcessFolder;
import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator.ProcessLookup;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import com.holdmyspot.cat.common.secrets.ProcessHandleTerminatorSecrets;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;
import java.util.function.LongFunction;

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
	 * Verifies that a recorded process is asked to stop after the wall clock was set, which moves the boot time that
	 * Linux derives from it, between the recording of the process's start and the request. The terminator reads the
	 * start of the test's parent process, which stands for the recorded process, from a process folder in place of
	 * {@code /proc}, and asks a fake process with the parent's identifier to stop, so that no real process stops.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void stopsRecordedProcessAfterClockIsSet() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			long bootTime = 1_794_069_622;
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("proc")), bootTime);
			long pid = ProcessHandle.current().parent().orElseThrow().pid();
			folder.start(pid, "java", 4000);
			HarnessProcesses starts = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
			String start = starts.find(pid).flatMap(ProcessDescription::start).orElseThrow();
			// The wall clock is set an hour back
			folder.setBootTime(bootTime - 3600);
			FakeProcessHandle driver = new FakeProcessHandle(1, "1", true);
			FakeProcessHandle process = new FakeProcessHandle(pid, "1", true);
			ProcessHandleTerminator terminator = terminator(starts, driver, process);

			boolean terminated = terminator.terminate(pid, start);

			requireThat(terminated, "terminated").isTrue();
			requireThat(process.stopRequested(), "process.stopRequested").isTrue();
		}
	}

	/**
	 * Verifies that a reused identifier or an unknown start leaves the process and its children alone.
	 */
	@Test
	public void unverifiedProcessIsLeftAlone()
	{
		// Requirement: R-E11
		FakeProcessHandle driver = new FakeProcessHandle(1, "1", true);
		FakeProcessHandle child = new FakeProcessHandle(3, "1", true);
		for (String actualStart : new String[]{"2", null})
		{
			FakeProcessHandle process = new FakeProcessHandle(2, actualStart, true, child);
			ProcessHandleTerminator terminator = terminator(driver, process);

			boolean terminated = terminator.terminate(process.pid(), "1");

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
		FakeProcessHandle driver = new FakeProcessHandle(1, "1", true);
		FakeProcessHandle grandchild = new FakeProcessHandle(4, "1", true);
		FakeProcessHandle child = new FakeProcessHandle(3, "1", true, grandchild);
		FakeProcessHandle process = new FakeProcessHandle(2, "1", true, child);
		FakeProcessHandle unrelated = new FakeProcessHandle(5, "1", true);
		ProcessHandleTerminator terminator = terminator(driver, process, child, grandchild, unrelated);

		boolean terminated = terminator.terminate(process.pid(), "1");

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
		FakeProcessHandle child = new FakeProcessHandle(2, "1", true);
		FakeProcessHandle driver = new FakeProcessHandle(1, "1", true, child);
		ProcessHandleTerminator terminator = terminator(driver, driver, child);

		boolean terminated = terminator.terminate(driver.pid(), "1");

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
		FakeProcessHandle driver = new FakeProcessHandle(1, "1", true);
		FakeProcessHandle child = new FakeProcessHandle(3, "1", true);
		FakeProcessHandle exited = new FakeProcessHandle(2, "1", false, child);
		ProcessHandleTerminator terminator = terminator(driver, exited, child);

		boolean terminatedExited = terminator.terminate(exited.pid(), "1");
		boolean terminatedMissing = terminator.terminate(4, "1");

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
		return terminator(FakeProcessHandle.processes(processes), driver, processes);
	}

	/**
	 * Creates a terminator with an in-memory process table and the supplied process starts.
	 *
	 * @param starts    reports the starts of running processes
	 * @param driver    the driver's own process
	 * @param processes the processes available by identifier
	 * @return the terminator
	 */
	private static ProcessHandleTerminator terminator(HarnessProcesses starts, FakeProcessHandle driver,
		FakeProcessHandle... processes)
	{
		LongFunction<Optional<ProcessHandle>> table = FakeProcessHandle.table(processes);
		return ProcessHandleTerminatorSecrets.getProcessHandleTerminatorAccess().create(starts, new ProcessLookup()
		{
			@Override
			public Optional<ProcessHandle> find(long pid)
			{
				return table.apply(pid);
			}

			@Override
			public ProcessHandle current()
			{
				return driver;
			}
		});
	}
}
