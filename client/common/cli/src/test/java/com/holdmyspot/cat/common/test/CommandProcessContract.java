package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the same live-process checks against each supplied process starter.
 */
public final class CommandProcessContract
{
	/**
	 * The maximum time each process check waits for its child to exit.
	 */
	private static final Duration PROCESS_WAIT = Duration.ofSeconds(10);
	private final Subject subject;

	/**
	 * Creates live-process checks that obtain a separate fixture for each case.
	 *
	 * @param subject creates a process fixture for each scenario
	 */
	public CommandProcessContract(Subject subject)
	{
		requireThat(subject, "subject").isNotNull();
		this.subject = subject;
	}

	/**
	 * Runs each live-process case with its own process fixture.
	 *
	 * @throws IOException if a process stream cannot be read or closed
	 * @throws InterruptedException if a process wait is interrupted
	 */
	public void verify() throws IOException, InterruptedException
	{
		processRemainsPendingUntilCompletion();
		completionDeliversStatusAndOutput();
		terminationCompletesExitFuture();
		closeReleasesOutputStream();
	}

	/**
	 * Verifies that a live process stays pending until its fixture explicitly completes it.
	 *
	 * @throws IOException if a process stream cannot be closed
	 * @throws InterruptedException if a process wait is interrupted
	 */
	public void processRemainsPendingUntilCompletion() throws IOException, InterruptedException
	{
		Scenario scenario = new Scenario(8, "pending stdout", "pending stderr");
		try (Fixture fixture = subject.start(scenario.status(), scenario.stdout(), scenario.stderr());
			Process process = fixture.process())
		{
			CompletableFuture<Process> exit = process.onExit();

			requireThat(process.isAlive(), "alive").isTrue();
			requireExitValueUnavailable(process);
			requireThat(process.waitFor(0, TimeUnit.MILLISECONDS), "zeroWaitCompleted").isFalse();
			requireThat(exit.isDone(), "exitFutureCompleted").isFalse();

			fixture.complete();
			requireThat(await(exit), "exitedProcess").isEqualTo(process);
		}
	}

	/**
	 * Verifies that completion publishes the selected status and both output payloads through the process adapter.
	 *
	 * @throws IOException if a process stream cannot be read or closed
	 * @throws InterruptedException if a process wait is interrupted
	 */
	public void completionDeliversStatusAndOutput() throws IOException, InterruptedException
	{
		Scenario scenario = new Scenario(8, "distinct stdout payload", "distinct stderr payload");
		try (Fixture fixture = subject.start(scenario.status(), scenario.stdout(), scenario.stderr());
			Process process = fixture.process())
		{
			CompletableFuture<Process> exit = process.onExit();
			fixture.complete();
			requireThat(await(exit), "exitedProcess").isEqualTo(process);

			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			requireThat(process.isAlive(), "alive").isFalse();
			requireThat(process.waitFor(), "waitStatus").isEqualTo(scenario.status());
			requireThat(process.exitValue(), "exitStatus").isEqualTo(scenario.status());
			requireThat(output, "output").contains(scenario.stdout()).contains(scenario.stderr());
		}
	}

	/**
	 * Verifies that destroying a live process completes its exit future and makes its status available.
	 *
	 * @throws IOException if a process stream cannot be closed
	 * @throws InterruptedException if a process wait is interrupted
	 */
	public void terminationCompletesExitFuture() throws IOException, InterruptedException
	{
		Scenario scenario = new Scenario(8, "termination stdout", "termination stderr");
		try (Fixture fixture = subject.start(scenario.status(), scenario.stdout(), scenario.stderr());
			Process process = fixture.process())
		{
			CompletableFuture<Process> exit = process.onExit();
			requireThat(process.isAlive(), "alive").isTrue();

			process.destroy();
			requireThat(await(exit), "exitedProcess").isEqualTo(process);

			requireThat(process.isAlive(), "alive").isFalse();
			requireThat(process.waitFor(), "waitStatus").isEqualTo(process.exitValue());
		}
	}

	/**
	 * Verifies that closing a completed process closes the output endpoint already captured by its consumer.
	 *
	 * @throws IOException if a process stream cannot be read or closed
	 * @throws InterruptedException if a process wait is interrupted
	 */
	public void closeReleasesOutputStream() throws IOException, InterruptedException
	{
		Scenario scenario = new Scenario(8, "close stdout payload", "close stderr payload");
		try (Fixture fixture = subject.start(scenario.status(), scenario.stdout(), scenario.stderr());
			InputStream output = fixture.process().getInputStream())
		{
			fixture.complete();
			await(fixture.process().onExit());
			try (Process process = fixture.process())
			{
				requireThat(process.isAlive(), "alive").isFalse();
			}
			requireOutputEndpointClosed(output);
		}
	}

	/**
	 * Waits for a process to exit within the contract's bound.
	 *
	 * @param exit the process's exit future
	 * @return the same process instance published by the future
	 * @throws InterruptedException if the wait is interrupted
	 */
	private static Process await(CompletableFuture<Process> exit) throws InterruptedException
	{
		try
		{
			return exit.get(PROCESS_WAIT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (ExecutionException | TimeoutException e)
		{
			throw new AssertionError("The process did not exit within " + PROCESS_WAIT, e);
		}
	}

	/**
	 * Verifies that a live process does not publish an exit status.
	 *
	 * @param process the process to inspect
	 */
	private static void requireExitValueUnavailable(Process process)
	{
		try
		{
			process.exitValue();
		}
		catch (IllegalThreadStateException _)
		{
			return;
		}
		throw new AssertionError("A live process published an exit status");
	}

	/**
	 * Verifies that reading the captured process output endpoint fails after the process closes it.
	 *
	 * @param output the process output endpoint captured before process closure
	 * @throws IOException if the endpoint cannot be read
	 */
	private static void requireOutputEndpointClosed(InputStream output) throws IOException
	{
		try
		{
			output.read();
		}
		catch (IOException _)
		{
			return;
		}
		throw new AssertionError("The process output endpoint remained open after close");
	}

	/**
	 * Supplies one fresh process fixture for each contract case.
	 *
	 * @return the process fixture
	 */
	@FunctionalInterface
	public interface Subject
	{
		/**
		 * Creates a fresh process fixture with the selected status and output payloads.
		 *
		 * @param status the terminal process status
		 * @param stdout the standard-output payload
		 * @param stderr the standard-error payload
		 * @return the new process fixture
		 * @throws IOException if the fixture cannot be started
		 * @throws InterruptedException if fixture setup is interrupted
		 */
		Fixture start(int status, String stdout, String stderr) throws IOException, InterruptedException;
	}

	/**
	 * Holds one process and controls its normal completion.
	 */
	public interface Fixture extends AutoCloseable
	{
		/**
		 * Returns the process observed by the contract.
		 *
		 * @return the process
		 */
		Process process();

		/**
		 * Completes the process with the selected status and output.
		 *
		 * @throws IOException if completion control cannot be delivered
		 */
		void complete() throws IOException;

		@Override
		void close() throws IOException;
	}

	/**
	 * The selected result that a process fixture publishes when completed.
	 *
	 * @param status the terminal process status
	 * @param stdout the standard-output payload
	 * @param stderr the standard-error payload
	 */
	private record Scenario(int status, String stdout, String stderr)
	{
	}
}
