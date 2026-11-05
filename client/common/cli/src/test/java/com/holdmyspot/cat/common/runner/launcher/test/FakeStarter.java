package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts a {@link FakeRunner} for each requested session, in place of a child's runner process.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeStarter implements RequestedSessionStarter
{
	private static final Duration WAIT = Duration.ofSeconds(5);
	private final List<FakeRunner> runners = new ArrayList<>();
	private int exitStatus;

	/**
	 * Creates a starter whose runners exit with status {@code 0}.
	 */
	public FakeStarter()
	{
	}

	/**
	 * Sets the status with which later runners exit after the {@code complete} record.
	 *
	 * @param exitStatus the status
	 */
	public void exitWith(int exitStatus)
	{
		synchronized (this)
		{
			this.exitStatus = exitStatus;
		}
	}

	@Override
	public Process start(String harness, List<String> arguments, Path directory)
	{
		synchronized (this)
		{
			FakeRunner runner = new FakeRunner(harness, arguments, exitStatus);
			runners.add(runner);
			notifyAll();
			return runner.process();
		}
	}

	/**
	 * Returns the runners started so far.
	 *
	 * @return the runners, in start order
	 */
	public List<FakeRunner> runners()
	{
		synchronized (this)
		{
			return List.copyOf(runners);
		}
	}

	/**
	 * Waits until a runner has started.
	 *
	 * @return the first runner
	 * @throws InterruptedException if the thread is interrupted while waiting
	 * @throws AssertionError       if no runner starts in time
	 */
	public FakeRunner awaitRunner() throws InterruptedException
	{
		long deadline = System.nanoTime() + WAIT.toNanos();
		synchronized (this)
		{
			while (runners.isEmpty())
			{
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0)
					throw new AssertionError("No runner started");
				TimeUnit.NANOSECONDS.timedWait(this, remaining);
			}
			return runners.getFirst();
		}
	}
}
