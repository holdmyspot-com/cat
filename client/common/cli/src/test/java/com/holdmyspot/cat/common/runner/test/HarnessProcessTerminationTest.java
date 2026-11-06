package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessProcessTermination;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Tests that a terminated run's process tree stops without starting processes.
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
	 * Verifies that ending a run stops its process, children and grandchildren.
	 *
	 * @throws IOException if termination fails
	 */
	@Test
	public void terminatesVisibleChildren() throws IOException
	{
		FakeProcessHandle grandchild = new FakeProcessHandle(3, Instant.EPOCH, true);
		FakeProcessHandle child = new FakeProcessHandle(2, Instant.EPOCH, true, grandchild);
		FakeProcessHandle parent = new FakeProcessHandle(1, Instant.EPOCH, true, child);

		HarnessProcessTermination.terminate(new FakeProcess(parent), "harness");

		requireThat(parent.isAlive(), "parent.alive").isFalse();
		requireThat(child.isAlive(), "child.alive").isFalse();
		requireThat(grandchild.isAlive(), "grandchild.alive").isFalse();
	}

	/**
	 * Verifies that shutdown stops the run's process, children and grandchildren.
	 */
	@Test
	public void immediatelyTerminatesVisibleChildren()
	{
		FakeProcessHandle grandchild = new FakeProcessHandle(3, Instant.EPOCH, true);
		FakeProcessHandle child = new FakeProcessHandle(2, Instant.EPOCH, true, grandchild);
		FakeProcessHandle parent = new FakeProcessHandle(1, Instant.EPOCH, true, child);

		HarnessProcessTermination.terminateImmediately(new FakeProcess(parent));

		requireThat(parent.isAlive(), "parent.alive").isFalse();
		requireThat(child.isAlive(), "child.alive").isFalse();
		requireThat(grandchild.isAlive(), "grandchild.alive").isFalse();
	}

	/**
	 * Supplies a process whose lifecycle is represented by its in-memory handle.
	 */
	private static final class FakeProcess extends Process
	{
		private final FakeProcessHandle handle;

		/**
		 * Creates a process backed by the supplied handle.
		 *
		 * @param handle the process identity and lifecycle
		 */
		private FakeProcess(FakeProcessHandle handle)
		{
			this.handle = handle;
		}

		@Override
		public ProcessHandle toHandle()
		{
			return handle;
		}

		@Override
		public boolean isAlive()
		{
			return handle.isAlive();
		}

		@Override
		public void destroy()
		{
			handle.destroy();
		}

		@Override
		public Process destroyForcibly()
		{
			handle.destroyForcibly();
			return this;
		}

		@Override
		public int waitFor()
		{
			return exitValue();
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit)
		{
			return !isAlive();
		}

		@Override
		public int exitValue()
		{
			if (isAlive())
				throw new IllegalThreadStateException("Process is still running");
			return 0;
		}

		@Override
		public OutputStream getOutputStream()
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public InputStream getInputStream()
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public InputStream getErrorStream()
		{
			throw new UnsupportedOperationException();
		}
	}
}
