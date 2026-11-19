package com.holdmyspot.cat.common.runner;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Stops the processes that a runner launcher started when the launcher ends. A runner launcher that a harness session
 * runs starts the new session as its own descendant, outside that session's sandbox, so nothing that it started
 * outlives it.
 * <p>
 * The stop asks the launcher's children to stop before the rest of its descendants, and waits a while for them, so
 * that a runner among them sees its own stop before its harness ends and leaves its run unended, as a run whose runner
 * stopped stays, so that its harness session can continue.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ProcessTreeStop
{
	/**
	 * How long the stop waits for the launcher's children before it asks their descendants to stop.
	 */
	private static final Duration CHILDREN_WAIT = Duration.ofSeconds(5);
	/**
	 * Set once the Java runtime of this process has begun to stop the processes that it started.
	 */
	private static final AtomicBoolean STOPPING = new AtomicBoolean();

	/**
	 * Prevents instantiation.
	 */
	private ProcessTreeStop()
	{
	}

	/**
	 * Has every descendant of the current process stopped when the Java runtime shuts down. From then on,
	 * {@link #stopping()} returns {@code true}.
	 */
	public static void onExit()
	{
		Runnable stop = stop(ProcessHandle.current());
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			STOPPING.set(true);
			stop.run();
		}));
	}

	/**
	 * Indicates whether this process has begun to stop the processes that it started because its Java runtime is
	 * shutting down, as {@link #onExit()} arranges. A runner that is stopping records no end for its run.
	 *
	 * @return {@code true} if this process is stopping
	 */
	public static boolean stopping()
	{
		return STOPPING.get();
	}

	/**
	 * Has every descendant of a process stopped when the process ends.
	 *
	 * @param exitHooks receives the work that runs when the process ends
	 * @param launcher  the process
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static void onExit(Consumer<Runnable> exitHooks, ProcessHandle launcher)
	{
		requireThat(exitHooks, "exitHooks").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		exitHooks.accept(stop(launcher));
	}

	/**
	 * Returns the work that stops every descendant of a process: its children first, and once they have ended, or
	 * after {@link #CHILDREN_WAIT}, the descendants that remain.
	 *
	 * @param launcher the process
	 * @return the work
	 */
	private static Runnable stop(ProcessHandle launcher)
	{
		assert launcher != null;
		return () ->
		{
			List<ProcessHandle> descendants = launcher.descendants().toList();
			List<ProcessHandle> children = launcher.children().toList();
			children.forEach(ProcessHandle::destroy);
			try
			{
				CompletableFuture.allOf(children.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new)).
					get(CHILDREN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
			}
			catch (ExecutionException | TimeoutException _)
			{
				// The descendants that remain are stopped below, whatever their parents did
			}
			descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
		};
	}
}
