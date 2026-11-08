package com.holdmyspot.cat.common.runner;

import java.util.function.Consumer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Stops the processes that a runner launcher started when the launcher ends. A runner launcher that a harness session
 * runs starts the new session as its own descendant, outside that session's sandbox, so nothing that it started
 * outlives it.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class ProcessTreeStop
{
	/**
	 * Prevents instantiation.
	 */
	private ProcessTreeStop()
	{
	}

	/**
	 * Has every descendant of the current process stopped when the Java runtime shuts down.
	 */
	public static void onExit()
	{
		onExit(work -> Runtime.getRuntime().addShutdownHook(new Thread(work)), ProcessHandle.current());
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
		exitHooks.accept(() -> launcher.descendants().forEach(ProcessHandle::destroy));
	}
}
