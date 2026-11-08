package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;

/**
 * Runs the {@link ProcessTable} contract against {@link FakeProcessTable}, so that the tests that hold their processes
 * in it see what the operating system's table would report.
 */
public final class FakeProcessTableTest extends ProcessTableContract
{
	/**
	 * Creates the test.
	 */
	public FakeProcessTableTest()
	{
	}

	@Override
	protected LiveProcess start()
	{
		FakeProcessTable table = new FakeProcessTable();
		return new FakeProcess(table, table.start(3));
	}

	/**
	 * A process in a fake table.
	 *
	 * @param table   the table
	 * @param process the process
	 */
	private record FakeProcess(FakeProcessTable table, ProcessIdentity process) implements LiveProcess
	{
		@Override
		public void end()
		{
			table.end(process);
		}

		@Override
		public void close()
		{
			table.end(process);
		}
	}
}
