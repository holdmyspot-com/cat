package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.ProcessTreeStop;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Plays, in a separate JVM for {@link NestedSessionManagerTest}, a runner whose harness reported its session: it
 * records its nested session's end once its harness ends, as a runner does, and it stops its harness when it is
 * stopped, as a runner launcher does, and its process ends only once it has handled its harness's end. Its harness is a
 * second JVM that runs until it is stopped.
 */
public final class InterruptedRunnerProcess
{
	/**
	 * The harness session identifier that the nested session records.
	 */
	public static final String HARNESS_SESSION_ID = "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90";

	/**
	 * Prevents instantiation.
	 */
	private InterruptedRunnerProcess()
	{
	}

	/**
	 * With {@code runner} and a results folder, creates a nested session there, starts its harness, prints the nested
	 * session's directory and the harness's process identifier, and records the nested session failed once the harness
	 * ends. With {@code harness}, runs until it is stopped.
	 *
	 * @param args {@code runner} and the absolute results folder, or {@code harness}
	 * @throws IOException          if the nested session cannot be created or its harness cannot start
	 * @throws InterruptedException if the runner is interrupted while it waits for its harness
	 */
	public static void main(String[] args) throws IOException, InterruptedException
	{
		if (args[0].equals("harness"))
		{
			// The harness runs until it is stopped
			new CountDownLatch(1).await();
			return;
		}
		ProcessTreeStop.onExit();
		// The runner's process ends only once the runner has handled its harness's end, so that its record of the end, if
		// it makes one, is always in place
		CountDownLatch handled = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() ->
		{
			try
			{
				handled.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
			}
		}));
		NestedSessionManager manager = new NestedSessionManager(new FileLocks(), Path.of(args[1]), Clock.systemUTC(),
			Yielding.fileReplacer());
		NestedSession nestedSession = manager.create("harness");
		manager.recordHarnessSession(nestedSession, HARNESS_SESSION_ID);
		try (Process harness = new ProcessBuilder(List.of(ProcessHandle.current().info().command().orElseThrow(), "-cp",
			System.getProperty("java.class.path"), InterruptedRunnerProcess.class.getName(), "harness")).
			inheritIO().start())
		{
			System.out.println(nestedSession.directory() + " " + harness.pid());
			System.out.flush();
			int status = harness.waitFor();
			try
			{
				manager.fail(nestedSession, "The harness exited with status " + status);
				manager.pruneRuntime(nestedSession);
			}
			finally
			{
				handled.countDown();
			}
		}
	}
}
