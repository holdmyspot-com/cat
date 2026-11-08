package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ProcessFolder;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.secrets.HarnessProcessesSecrets;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Runs the {@link HarnessProcesses} contract against {@link HarnessProcesses#system()}, reading Linux's process files
 * from a folder in place of {@code /proc} that shows the start of the test's own process and not that of its parent.
 */
public final class SystemProcessesContractTest extends HarnessProcessesContract
{
	/**
	 * An identifier that no process has: Linux's identifiers stay below 4194304, macOS's below 100000, and Windows's
	 * are multiples of 4.
	 */
	private static final long NOT_RUNNING = 4_194_305;

	/**
	 * Creates the test.
	 */
	public SystemProcessesContractTest()
	{
	}

	@Override
	protected Fixture create() throws IOException
	{
		TemporaryDirectories cleanup = new TemporaryDirectories();
		try
		{
			ProcessFolder folder = new ProcessFolder(cleanup.add(Files.createTempDirectory("cat-proc-")),
				1_794_069_622);
			long running = ProcessHandle.current().pid();
			folder.start(running, "java", 4000);
			long withoutStart = ProcessHandle.current().parent().orElseThrow().pid();
			HarnessProcesses processes = HarnessProcessesSecrets.getHarnessProcessesAccess().system(folder.path());
			return new SystemFixture(cleanup, processes, running, withoutStart);
		}
		catch (IOException | RuntimeException e)
		{
			cleanup.close();
			throw e;
		}
	}

	/**
	 * The test's own process and its parent, read from a process folder.
	 *
	 * @param cleanup             removes the process folder
	 * @param processes           the lookup
	 * @param running             the test's own process
	 * @param runningWithoutStart the test's parent process
	 */
	private record SystemFixture(TemporaryDirectories cleanup, HarnessProcesses processes, long running,
		long runningWithoutStart) implements Fixture
	{
		@Override
		public long notRunning()
		{
			return NOT_RUNNING;
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
