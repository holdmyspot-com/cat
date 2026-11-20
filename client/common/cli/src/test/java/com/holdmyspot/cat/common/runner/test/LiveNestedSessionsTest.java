package com.holdmyspot.cat.common.runner.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.LiveNestedSession;
import com.holdmyspot.cat.common.runner.LiveNestedSessions;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tests {@link LiveNestedSessions}.
 */
public final class LiveNestedSessionsTest
{
	/**
	 * Creates the test fixture.
	 */
	public LiveNestedSessionsTest()
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
			LiveNestedSessions sessions = new LiveNestedSessions())
		{
			NestedSession nestedSession = new NestedSession("harness", "session-1",
				cleanup.add(Files.createTempDirectory("cat-session-")));
			sessions.add(new LiveNestedSession(nestedSession, process, new TestHarnessRunnerScope()));
			HarnessProcessResult first = sessions.sendTurn("session-1", "first");
			HarnessProcessResult second = sessions.sendTurn("session-1", "second");
			requireThat(first.completed(), "firstCompleted").isTrue();
			requireThat(first.assistantResponse(), "firstAssistantResponse").isEqualTo("one");
			requireThat(second.completed(), "secondCompleted").isTrue();
			requireThat(second.assistantResponse(), "secondAssistantResponse").isEqualTo("two");
			sessions.complete("session-1");

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
			LiveNestedSessions sessions = new LiveNestedSessions())
		{
			sessions.add(new LiveNestedSession(new NestedSession("harness", "session-1",
				cleanup.add(Files.createTempDirectory("cat-session-"))),
				failingToClose(cleanup.add(Files.createTempDirectory("cat-session-"))), new TestHarnessRunnerScope()));
			sessions.add(new LiveNestedSession(new NestedSession("harness", "session-2",
				cleanup.add(Files.createTempDirectory("cat-session-"))),
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
