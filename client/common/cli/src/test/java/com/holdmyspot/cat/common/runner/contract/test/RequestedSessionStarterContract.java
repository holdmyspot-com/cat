package com.holdmyspot.cat.common.runner.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

/**
 * The behaviour that every {@link RequestedSessionStarter} shares: it starts the runner of the requested harness with
 * the options and the folder that its caller gives, the runner's standard error goes to that folder's
 * {@value SessionLauncherChannel#RUNNER_STDERR_FILE}, and a harness that it has no runner for is refused. A test class
 * of each starter extends this class. A starter that starts a real runner does so through a stand-in for the operating
 * system's process start, so that no runner, harness or model starts.
 */
public abstract class RequestedSessionStarterContract
{
	/**
	 * Creates the contract.
	 */
	protected RequestedSessionStarterContract()
	{
	}

	/**
	 * A runner as its starter started it.
	 *
	 * @param harness   the identifier of the harness whose runner started
	 * @param options   the runner's options
	 * @param directory the folder that the runner started in
	 * @param stderr    the file that receives the runner's standard error
	 */
	public record StartedRunner(String harness, List<String> options, Path directory, Path stderr)
	{
	}

	/**
	 * A starter under test, and the runners that it has started.
	 */
	public interface Starts extends AutoCloseable
	{
		/**
		 * Returns the starter.
		 *
		 * @return the starter
		 */
		RequestedSessionStarter starter();

		/**
		 * Returns the runners that the starter has started.
		 *
		 * @return the runners, in start order
		 */
		List<StartedRunner> started();

		@Override
		void close() throws IOException;
	}

	/**
	 * Returns the identifier of a harness that the starter has a runner for.
	 *
	 * @return the identifier
	 */
	protected abstract String harness();

	/**
	 * Creates a starter that has started nothing.
	 *
	 * @return the starter and its runners
	 * @throws IOException if the starter's files cannot be created
	 */
	protected abstract Starts starts() throws IOException;

	/**
	 * Verifies that the starter starts the requested harness's runner with exactly the caller's options, in the
	 * caller's folder, and with its standard error going to that folder's
	 * {@value SessionLauncherChannel#RUNNER_STDERR_FILE}.
	 *
	 * @throws IOException if the starter fails
	 */
	@Test
	public void startsRequestedRunner() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope();
			Starts starts = starts())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "requested-runner-"));
			List<String> options = List.of("--model", "model-a", "--effort", "effort-a", "--project",
				directory.resolve("project").toString());

			try (Process runner = starts.starter().start(harness(), options, directory))
			{
				requireThat(starts.started(), "started").isEqualTo(List.of(new StartedRunner(harness(), options,
					directory, directory.resolve(SessionLauncherChannel.RUNNER_STDERR_FILE))));
				runner.destroy();
			}
		}
	}

	/**
	 * Verifies that the starter refuses a harness that it has no runner for, and starts nothing.
	 *
	 * @throws IOException if the starter fails
	 */
	@Test
	public void refusesUnsupportedHarness() throws IOException
	{
		// Requirement: R-I11
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope jvm = new TestJvmScope();
			Starts starts = starts())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "requested-runner-"));
			try (Process _ = starts.starter().start("harness-z", List.of(), directory))
			{
				throw new AssertionError("The starter started a runner of an unsupported harness");
			}
			catch (IllegalArgumentException _)
			{
				requireThat(starts.started(), "started").isEmpty();
			}
		}
	}
}
