package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import java.io.IOException;
import java.nio.file.Files;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessRunSession}.
 */
public final class HarnessRunSessionTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunSessionTest()
	{
	}
	/**
	 * Verifies multiple turns use one live harness process.
	 *
	 * @throws IOException if the fake process fails
	 */
	@Test
	public void sendsTurnsToSameProcess() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FakeProcess process = new FakeProcess();
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			try (HarnessRunSession session = new HarnessRunSession(run, process, new TestHarnessRunnerScope()))
			{
				session.sendTurn("first");
				session.sendTurn("second");
			}

			requireThat(process.turns, "turns").isEqualTo(2);
			requireThat(process.closed, "closed").isTrue();
		}
	}

	/**
	 * Verifies repeated closure does not close the owned process more than once.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void closesOnlyOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FakeProcess process = new FakeProcess();
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			try (HarnessRunSession session = new HarnessRunSession(run, process, new TestHarnessRunnerScope()))
			{
				closeTwice(session);
			}

			requireThat(process.closeCalls, "closeCalls").isEqualTo(1);
		}
	}

	/**
	 * Closes a session twice to verify its idempotent lifecycle contract.
	 *
	 * @param session the session to close
	 * @throws IOException if closure fails
	 */
	private static void closeTwice(HarnessRunSession session) throws IOException
	{
		assert session != null;
		session.close();
		session.close();
	}

	private static final class FakeProcess implements HarnessProcess
	{
		private int turns;
		private boolean closed;
		private int closeCalls;

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
			closeCalls += 1;
			closed = true;
		}
	}
}
