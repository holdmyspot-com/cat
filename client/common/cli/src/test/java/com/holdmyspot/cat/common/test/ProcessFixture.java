package com.holdmyspot.cat.common.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Provides Java processes that exit successfully or wait in a parent-child tree.
 */
public final class ProcessFixture
{
	/**
	 * Selects the process behavior.
	 */
	public enum Mode
	{
		/**
		 * Exits successfully.
		 */
		EXIT,
		/**
		 * Starts one child, reports readiness, and waits for the child to exit.
		 */
		TREE,
		/**
		 * Reports readiness and waits until explicitly terminated.
		 */
		CHILD
	}

	/**
	 * Creates a command using the current Java launcher. The fixture uses only the JDK, so the command puts only the
	 * directory or archive that holds this class on the class path and starts with the fastest startup settings:
	 * resolving the test module's whole module path more than quadruples the time that each process takes to start.
	 *
	 * @param mode the process behavior
	 * @return the process builder
	 */
	public static ProcessBuilder builder(Mode mode)
	{
		String java = ProcessHandle.current().info().command().orElseThrow();
		String classPath;
		try
		{
			classPath = Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).
				toString();
		}
		catch (URISyntaxException e)
		{
			throw new AssertionError(e);
		}
		return new ProcessBuilder(java, "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-XX:-UsePerfData",
			"-Xshare:auto", "--class-path", classPath, ProcessFixture.class.getName(), mode.name());
	}

	/**
	 * Waits for a parent to report that its child is ready.
	 *
	 * @param process the parent process
	 * @throws IOException if the tree does not become ready
	 */
	public static void awaitStarted(Process process) throws IOException
	{
		FutureTask<String> readiness = new FutureTask<>(() ->
		{
			try (BufferedReader output = process.inputReader(UTF_8))
			{
				return output.readLine();
			}
		});
		Thread reader = Thread.ofVirtual().start(readiness);
		try
		{
			if (!"started".equals(readiness.get(5, TimeUnit.SECONDS)))
				throw new IOException("Java process did not report readiness");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the Java process", e);
		}
		catch (ExecutionException | TimeoutException e)
		{
			throw new IOException("Could not read Java process readiness", e);
		}
		finally
		{
			reader.interrupt();
		}
	}

	/**
	 * Runs the requested fixture. The waiting child does not exit when its parent is killed, so a termination
	 * test must terminate the child explicitly to pass.
	 *
	 * @param args the fixture mode
	 * @throws IOException if the child cannot start or report readiness
	 * @throws InterruptedException if waiting is interrupted
	 */
	public static void main(String[] args) throws IOException, InterruptedException
	{
		switch (Mode.valueOf(args[0]))
		{
			case EXIT ->
			{
			}
			case TREE ->
			{
				try (Process child = builder(Mode.CHILD).redirectError(ProcessBuilder.Redirect.DISCARD).start())
				{
					awaitStarted(child);
					System.out.println("started");
					System.out.flush();
					child.waitFor();
				}
			}
			case CHILD ->
			{
				System.out.println("started");
				System.out.flush();
				new CountDownLatch(1).await();
			}
		}
	}

	/**
	 * Prevents construction.
	 */
	private ProcessFixture()
	{
	}
}
