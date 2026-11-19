package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Terminates a harness process and every descendant that remains visible to its process handle.
 */
public final class HarnessProcessTermination
{
	private static final Duration GRACEFUL_TERMINATION_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Stops the process tree before its runner publishes a terminal result.
	 *
	 * @param process the live harness process
	 *
	 * @param harnessName the harness name for diagnostics
	 *
	 * @throws IOException if interruption prevents termination from being confirmed
	 */
	public static void terminate(Process process, String harnessName) throws IOException
	{
		List<ProcessHandle> descendants = process.toHandle().descendants().toList();
		descendants.forEach(ProcessHandle::destroy);
		if (process.isAlive())
			process.destroy();
		try
		{
			if (!process.waitFor(GRACEFUL_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
			{
				process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				process.waitFor();
			}
			descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
			process.toHandle().descendants().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for " + harnessName + " and its descendants to exit", e);
		}
	}

	/**
	 * Stops a runner and every process that it started. The runner is asked to stop before its descendants, so that it
	 * sees its own stop before its harness ends and records no end for its run: a run whose runner its server stopped
	 * stays unended, so that its harness session can continue. The descendants that remain once the runner has ended
	 * are stopped too, forcibly if they outlast {@link #GRACEFUL_TERMINATION_TIMEOUT}.
	 *
	 * @param runner the runner
	 * @throws NullPointerException if {@code runner} is null
	 * @throws IOException          if interruption prevents termination from being confirmed
	 */
	public static void stopRunner(Process runner) throws IOException
	{
		requireThat(runner, "runner").isNotNull();
		List<ProcessHandle> descendants = runner.toHandle().descendants().toList();
		try
		{
			if (runner.isAlive())
			{
				runner.destroy();
				if (!runner.waitFor(GRACEFUL_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
				{
					runner.destroyForcibly();
					runner.waitFor();
				}
			}
			List<ProcessHandle> remaining = descendants.stream().filter(ProcessHandle::isAlive).toList();
			remaining.forEach(ProcessHandle::destroy);
			for (ProcessHandle descendant : remaining)
			{
				try
				{
					descendant.onExit().get(GRACEFUL_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
				}
				catch (ExecutionException | TimeoutException _)
				{
					descendant.destroyForcibly();
				}
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the runner and its descendants to exit", e);
		}
	}

	/**
	 * Immediately stops a process tree during JVM shutdown without waiting for each process to report its exit.
	 *
	 * @param process the process whose inherited children must not outlive the owning JVM
	 */
	public static void terminateImmediately(Process process)
	{
		process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
		if (process.isAlive())
			process.destroyForcibly();
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessProcessTermination()
	{
	}
}
