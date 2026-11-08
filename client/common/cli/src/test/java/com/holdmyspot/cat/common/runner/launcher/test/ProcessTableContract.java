package com.holdmyspot.cat.common.runner.launcher.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;
import java.io.IOException;
import org.testng.annotations.Test;

/**
 * The behaviour that every {@link ProcessTable} implementation must provide, on which the request server's end with
 * its session rests: a live process is alive, and a process that ended, or an earlier process whose identifier a live
 * one reuses, is not. Each implementation's test extends this class.
 */
public abstract class ProcessTableContract
{
	/**
	 * Creates the contract tests.
	 */
	protected ProcessTableContract()
	{
	}

	/**
	 * Starts a process.
	 *
	 * @return the process and the table that reports it
	 * @throws IOException if the process cannot be started
	 */
	protected abstract LiveProcess start() throws IOException;

	/**
	 * A live process and the table that reports it.
	 */
	protected interface LiveProcess extends AutoCloseable
	{
		/**
		 * Returns the table.
		 *
		 * @return the table
		 */
		ProcessTable table();

		/**
		 * Returns the process.
		 *
		 * @return the process
		 */
		ProcessIdentity process();

		/**
		 * Ends the process and waits until it has ended.
		 *
		 * @throws IOException if the process does not end
		 */
		void end() throws IOException;

		/**
		 * Ends the process if it still runs.
		 *
		 * @throws IOException if the process does not end
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * Verifies that a live process is alive.
	 *
	 * @throws IOException if the process cannot be started or ended
	 */
	@Test
	public void liveProcessIsAlive() throws IOException
	{
		try (LiveProcess live = start())
		{
			requireThat(live.table().isAlive(live.process()), "isAlive").isTrue();
		}
	}

	/**
	 * Verifies that a process that ended is not alive, so that the request server ends with its session.
	 *
	 * @throws IOException if the process cannot be started or ended
	 */
	@Test
	public void endedProcessIsNotAlive() throws IOException
	{
		try (LiveProcess live = start())
		{
			live.end();

			requireThat(live.table().isAlive(live.process()), "isAlive").isFalse();
		}
	}

	/**
	 * Verifies that an earlier process whose identifier a live process has is not alive, so that the request server
	 * never takes the live process for its session's harness.
	 *
	 * @throws IOException if the process cannot be started or ended
	 */
	@Test
	public void earlierProcessIsNotAlive() throws IOException
	{
		try (LiveProcess live = start())
		{
			// No process that the test starts has the start "0", whatever form the table's starts have
			ProcessIdentity earlier = new ProcessIdentity(live.process().pid(), "0");

			requireThat(live.table().isAlive(earlier), "isAlive").isFalse();
		}
	}
}
