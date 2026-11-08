package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunSessions;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


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
		List<String> received = new ArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ScriptedProcess process = new ScriptedProcess(List.of(Turn.replies("one"), Turn.replies("two")), received);
			HarnessRunSessions sessions = new HarnessRunSessions())
		{
			HarnessRun run = new HarnessRun("harness", "run-1", cleanup.add(Files.createTempDirectory("cat-run-")));
			sessions.add(new HarnessRunSession(run, process, new TestHarnessRunnerScope()));
			sessions.sendTurn("run-1", "first");
			sessions.sendTurn("run-1", "second");
			sessions.complete("run-1");

			requireThat(received, "received").isEqualTo(List.of("first", "second"));
			requireThat(process.isAlive(), "alive").isFalse();
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
				failingToClose(cleanup.add(Files.createTempDirectory("cat-session-"))), new TestHarnessRunnerScope()));
			sessions.add(new HarnessRunSession(new HarnessRun("harness", "run-2",
				cleanup.add(Files.createTempDirectory("cat-run-"))),
				failingToClose(cleanup.add(Files.createTempDirectory("cat-session-"))), new TestHarnessRunnerScope()));
		}
		catch (IOException e)
		{
			requireThat(e.getSuppressed().length, "suppressedFailureCount").isEqualTo(1);
			return;
		}
		throw new AssertionError("Expected session shutdown to report both close failures");
	}

	/**
	 * Returns a process that fails to close because a file takes the place where it copies its session transcripts.
	 *
	 * @param directory a new directory for the process's files
	 * @return the process
	 * @throws IOException if the file cannot be written
	 */
	private static ScriptedProcess failingToClose(Path directory) throws IOException
	{
		Path copies = Files.writeString(directory.resolve("copies"), "");
		return new ScriptedProcess(List.of(), new ArrayList<>(), directory.resolve("home/session.jsonl"),
			new HarnessSessionTranscripts(directory.resolve("home"), copies, name -> name.endsWith(".jsonl")));
	}
}
