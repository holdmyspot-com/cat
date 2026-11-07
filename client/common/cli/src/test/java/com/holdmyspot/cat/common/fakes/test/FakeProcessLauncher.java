package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.ExecutableFiles.ProcessLauncher;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Stands in for the operating system's process start: it records each configured process and returns, in place of the
 * started program, a process that reads and writes nothing and runs until it is destroyed. No operating-system process
 * starts.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeProcessLauncher implements ProcessLauncher
{
	private static final Instant STARTED_AT = Instant.parse("2026-11-05T12:00:00Z");
	private final List<ProcessBuilder> started = new ArrayList<>();

	/**
	 * Creates a launcher that has started nothing.
	 */
	public FakeProcessLauncher()
	{
	}

	@Override
	public Process start(ProcessBuilder builder)
	{
		synchronized (this)
		{
			started.add(builder);
			return new RunningProcess(Long.MAX_VALUE - started.size());
		}
	}

	/**
	 * Returns the configured processes that were started.
	 *
	 * @return the configurations, in start order
	 */
	public List<ProcessBuilder> started()
	{
		synchronized (this)
		{
			return List.copyOf(started);
		}
	}

	/**
	 * A started program that reads and writes nothing and runs until it is destroyed.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class RunningProcess extends Process
	{
		private final FakeProcessHandle handle;
		private final CountDownLatch ended = new CountDownLatch(1);

		/**
		 * Creates a process.
		 *
		 * @param pid the process identifier
		 */
		RunningProcess(long pid)
		{
			this.handle = new FakeProcessHandle(pid, STARTED_AT, true);
		}

		@Override
		public OutputStream getOutputStream()
		{
			return OutputStream.nullOutputStream();
		}

		@Override
		public InputStream getInputStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			ended.await();
			return exitValue();
		}

		@Override
		public int exitValue()
		{
			if (ended.getCount() != 0)
				throw new IllegalThreadStateException("The process has not exited");
			return 143;
		}

		@Override
		public void destroy()
		{
			ended.countDown();
		}

		@Override
		public ProcessHandle toHandle()
		{
			return handle;
		}
	}
}
