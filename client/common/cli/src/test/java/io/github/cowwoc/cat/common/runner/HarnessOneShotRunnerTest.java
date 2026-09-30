package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessOneShotRunner}.
 */
public final class HarnessOneShotRunnerTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessOneShotRunnerTest()
	{
	}

	/**
	 * Reserves the native turn, then persists terminal output before removing the successful run.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void persistsOutputAndRemovesSuccessfulRun() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-one-shot-"));
			HarnessRunManager manager = new HarnessRunManager(root, Clock.systemUTC());
			HarnessRun run = manager.create("harness");
			Path output = root.resolve("result.json");

			HarnessOneShotRunner.run(() -> new HarnessRunSession(run, new Process(), new HarnessRunnerScope()), manager,
				"prompt", output);

			requireThat(Files.readString(output), "output").isEqualTo("result");
			requireThat(Files.exists(run.directory()), "runDirectory").isFalse();
		}
	}

	private static final class Process implements HarnessProcess
	{
		private boolean reserved;

		@Override
		public void reserveTurn()
		{
			reserved = true;
		}

		@Override
		public HarnessProcessResult sendTurn(String request) throws IOException
		{
			assert request != null;
			if (!reserved)
				throw new IOException("Turn was not reserved before dispatch");
			reserved = false;
			return new HarnessProcessResult(0, "result", "");
		}

		@Override
		public boolean isAlive()
		{
			return true;
		}

		@Override
		public void close()
		{
		}
	}
}
