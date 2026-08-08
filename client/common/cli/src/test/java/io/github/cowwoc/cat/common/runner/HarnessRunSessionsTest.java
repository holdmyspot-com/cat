package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessRunSessions}.
 */
public final class HarnessRunSessionsTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunSessionsTest()
	{
	}
	/**
	 * Verifies a registered session receives multiple turns until explicit completion.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void routesTurnsUntilCompletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FakeProcess process = new FakeProcess();
			HarnessRunSessions sessions = new HarnessRunSessions())
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			sessions.add(new HarnessRunSession(run, process, new HarnessRunnerScope()));
			sessions.sendTurn("run-1", "first");
			sessions.sendTurn("run-1", "second");
			sessions.complete("run-1");

			requireThat(process.turns, "turns").isEqualTo(2);
			requireThat(process.closed, "closed").isTrue();
		}
	}

	/**
	 * Verifies shutdown retains every session-close failure.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void retainsAllShutdownFailures() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			HarnessRunSessions sessions = new HarnessRunSessions())
		{
			sessions.add(new HarnessRunSession(new HarnessRun("harness", "run-1",
				cleanup.add(Files.createTempDirectory("cat-run-"))),
				new FailingProcess("first"), new HarnessRunnerScope()));
			sessions.add(new HarnessRunSession(new HarnessRun("harness", "run-2",
				cleanup.add(Files.createTempDirectory("cat-run-"))),
				new FailingProcess("second"), new HarnessRunnerScope()));
		}
		catch (IOException e)
		{
			requireThat(e.getSuppressed().length, "suppressedFailureCount").isEqualTo(1);
			return;
		}
		throw new AssertionError("Expected session shutdown to report both close failures");
	}

	private static final class FakeProcess implements HarnessProcess
	{
		private int turns;
		private boolean closed;

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			++turns;
			return new HarnessProcessResult(0, "", "");
		}

		@Override
		public boolean isAlive()
		{
			return !closed;
		}

		@Override
		public void close()
		{
			closed = true;
		}
	}

	/**
	 * An harness process that fails during shutdown.
	 */
	private static final class FailingProcess implements HarnessProcess
	{
		private final String name;

		/**
		 * Creates a process that fails with the supplied name.
		 *
		 * @param name the failure name
		 */
		private FailingProcess(String name)
		{
			assert name != null;
			this.name = name;
		}

		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
			return new HarnessProcessResult(0, "", "");
		}

		@Override
		public boolean isAlive()
		{
			return true;
		}

		@Override
		public void close() throws IOException
		{
			throw new IOException(name);
		}
	}
}
