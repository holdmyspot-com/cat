package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
	 * @throws IOException if a turn fails
	 */
	@Test
	public void sendsTurnsToSameProcess() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			List<String> received = new ArrayList<>();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")), received);
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			try (HarnessRunSession session = new HarnessRunSession(run, process, new TestHarnessRunnerScope()))
			{
				session.sendTurn("first");
				session.sendTurn("second");
			}

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(process.isAlive(), "alive").isFalse();
		}
	}

	/**
	 * Verifies repeated closure does not close the owned process more than once. Closing the process copies its session
	 * transcripts, so once the place for the copies is taken by a file, closing the process again would fail.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void closesOnlyOnce() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-session-"));
			Path copies = directory.resolve("copies");
			ScriptedProcess process = new ScriptedProcess(List.of(), new ArrayList<>(),
				directory.resolve("home/session.jsonl"), new HarnessSessionTranscripts(directory.resolve("home"), copies,
				name -> name.endsWith(".jsonl")));
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			try (HarnessRunSession session = new HarnessRunSession(run, process, new TestHarnessRunnerScope()))
			{
				closeAndTakeCopiesPlace(session, copies);
			}
		}
	}

	/**
	 * Closes a session and then puts a file where its process copies its session transcripts, so that closing the
	 * process again would fail.
	 *
	 * @param session the session
	 * @param copies  the directory that receives the copies of the process's session transcripts
	 * @throws IOException if closing the session or replacing the directory fails
	 */
	private static void closeAndTakeCopiesPlace(HarnessRunSession session, Path copies) throws IOException
	{
		session.close();
		Files.delete(copies);
		Files.writeString(copies, "");
	}
}
