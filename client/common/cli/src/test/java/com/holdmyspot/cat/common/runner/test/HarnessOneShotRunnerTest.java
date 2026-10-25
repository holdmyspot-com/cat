package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessOneShotRunner;
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
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
	 * Verifies terminal output is persisted before the successful run is removed.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void persistsOutputAndRemovesSuccessfulRun() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-one-shot-"));
			HarnessRunManager manager = new HarnessRunManager(fileLocks, root,
				Clock.systemUTC(), Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			Path output = root.resolve("result.json");

			HarnessOneShotRunner.run(() -> new HarnessRunSession(run, new Process(), new TestHarnessRunnerScope()), manager,
				"prompt", output);

			requireThat(Files.readString(output), "output").isEqualTo("result");
			requireThat(Files.exists(run.directory()), "runDirectory").isFalse();
		}
	}

	private static final class Process implements HarnessProcess
	{
		@Override
		public HarnessProcessResult sendTurn(String request)
		{
			assert request != null;
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
