package com.holdmyspot.cat.common.runner.launcher.test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs each task on its own virtual thread and lets a test wait until every task has ended, or hold the tasks that
 * are submitted until the test releases them.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class TrackedExecutor extends AbstractExecutorService
{
	private static final Duration WAIT = Duration.ofSeconds(5);
	private final List<Thread> threads = new ArrayList<>();
	private final List<Runnable> held = new ArrayList<>();
	private boolean holding;
	private boolean shutdown;

	/**
	 * Creates an executor.
	 */
	public TrackedExecutor()
	{
	}

	@Override
	public void execute(Runnable command)
	{
		synchronized (this)
		{
			if (shutdown)
				throw new IllegalStateException("The executor is shut down");
			if (holding)
				held.add(command);
			else
				threads.add(Thread.ofVirtual().start(command));
		}
	}

	/**
	 * Makes the tasks that are submitted from now on wait, unstarted, until {@link #release()}, so that a test
	 * chooses what happens before they run.
	 */
	public void hold()
	{
		synchronized (this)
		{
			holding = true;
		}
	}

	/**
	 * Starts the tasks that were held and stops holding later ones. Does nothing if no task is held.
	 */
	public void release()
	{
		synchronized (this)
		{
			holding = false;
			for (Runnable command : held)
				threads.add(Thread.ofVirtual().start(command));
			held.clear();
		}
	}

	/**
	 * Waits until every task that has started, and every task that those tasks start, has ended.
	 *
	 * @throws InterruptedException if the thread is interrupted while waiting
	 * @throws AssertionError       if a task is held, or still running after five seconds
	 */
	public void awaitIdle() throws InterruptedException
	{
		long deadline = System.nanoTime() + WAIT.toNanos();
		int joined = 0;
		while (true)
		{
			Thread next;
			synchronized (this)
			{
				if (!held.isEmpty())
					throw new AssertionError("A task is held");
				if (joined == threads.size())
					return;
				next = threads.get(joined);
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0 || !next.join(Duration.ofNanos(remaining)))
				throw new AssertionError("A task is still running");
			joined += 1;
		}
	}

	@Override
	public void shutdown()
	{
		synchronized (this)
		{
			shutdown = true;
		}
	}

	@Override
	public List<Runnable> shutdownNow()
	{
		shutdown();
		return List.of();
	}

	@Override
	public boolean isShutdown()
	{
		synchronized (this)
		{
			return shutdown;
		}
	}

	@Override
	public boolean isTerminated()
	{
		synchronized (this)
		{
			return shutdown && threads.stream().noneMatch(Thread::isAlive);
		}
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
	{
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		List<Thread> started;
		synchronized (this)
		{
			started = List.copyOf(threads);
		}
		for (Thread thread : started)
		{
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0 || !thread.join(Duration.ofNanos(remaining)))
				return false;
		}
		return true;
	}
}
