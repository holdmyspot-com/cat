package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.CodexGetHistory;
import com.holdmyspot.cat.codex.secrets.CodexGetHistorySecrets;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexGetHistory}.
 */
public final class CodexGetHistoryTest
{
	private static final String PARENT_ID = "019a2b3c-0000-7000-8000-000000000002";
	private static final String FORK_ID = "019a2b3c-0000-7000-8000-000000000003";
	private static final String BEFORE_FORK = "message the parent sent before the fork";
	private static final String AFTER_FORK = "message the parent sent after the fork";
	private static final String FORK_OWN = "message the fork sent itself";

	/**
	 * Creates the test fixture.
	 */
	public CodexGetHistoryTest()
	{
	}

	/**
	 * Verifies an explicit Codex thread identifier selects its rollout transcript.
	 */
	@Test
	public void resolvesRequestedThreadTranscript() throws IOException
	{
		// Requirement: R-I5
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			String threadId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(codexHome.resolve("sessions/2026/08/14")).resolve(
				"rollout-2026-08-14T01-00-00-" + threadId + ".jsonl");
			Files.writeString(transcript, "{\"type\":\"session_meta\"}\n");

			String result = CodexGetHistorySecrets.getCodexGetHistoryAccess().
				execute(scope, Map.of("CODEX_HOME", codexHome.toString()),
				new String[]{threadId, "analyze"}, InputStream.nullInputStream());

			requireThat(result, "result").contains("transcript=" + transcript.toRealPath()).
				contains("records=1");
		}
	}

	/**
	 * Verifies that a {@code CODEX_HOME} that is a symbolic link finds the rollouts in the folder that it links to, as
	 * Codex does, and names that folder in the result.
	 */
	@Test
	public void followsCodexHomeLink() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			Path codexHome = Files.createDirectory(directory.resolve("target"));
			Path link = Files.createSymbolicLink(directory.resolve("link"), codexHome);
			String threadId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(codexHome.resolve("sessions/2026/08/14")).resolve(
				"rollout-2026-08-14T01-00-00-" + threadId + ".jsonl");
			Files.writeString(transcript, "{\"type\":\"session_meta\"}\n");

			String result = CodexGetHistorySecrets.getCodexGetHistoryAccess().
				execute(scope, Map.of("CODEX_HOME", link.toString()),
				new String[]{threadId, "analyze"}, InputStream.nullInputStream());

			requireThat(result, "result").contains("transcript=" + transcript.toRealPath());
		}
	}

	/**
	 * Verifies that a blank {@code CODEX_HOME} is not replaced by the default home, because Codex reads a value that
	 * is not empty as the name of its home, and that the refusal names the variable.
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*CODEX_HOME.*")
	public void rejectsBlankCodexHome() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			String threadId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(home.resolve(".codex/sessions/2026/08/14")).resolve(
				"rollout-2026-08-14T01-00-00-" + threadId + ".jsonl");
			Files.writeString(transcript, "{\"type\":\"session_meta\"}\n");

			CodexGetHistorySecrets.getCodexGetHistoryAccess().
				execute(scope, Map.of("CODEX_HOME", "  ", Platform.current().homeVariable(), home.toString()),
				new String[]{threadId, "analyze"}, InputStream.nullInputStream());
		}
	}

	/**
	 * Verifies that a {@code CODEX_HOME} naming a folder that does not exist is refused with the reason that Codex
	 * gives, rather than reporting a missing transcript directory inside it.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*CODEX_HOME names a folder that does not exist")
	public void rejectsMissingCodexHome() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-codex-history-"));

			CodexGetHistorySecrets.getCodexGetHistoryAccess().
				execute(scope, Map.of("CODEX_HOME", directory.resolve("missing").toString()),
				new String[]{UUID.randomUUID().toString(), "analyze"}, InputStream.nullInputStream());
		}
	}

	/**
	 * Verifies that without {@code CODEX_HOME} the rollout is read from {@code .codex} below {@code HOME}, the
	 * environment of an ordinary Codex tool shell.
	 */
	@Test
	public void readsRolloutBelowHomeWithoutCodexHome() throws IOException
	{
		// Requirement: R-I5
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			String threadId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(home.resolve(".codex/sessions/2026/08/14")).resolve(
				"rollout-2026-08-14T01-00-00-" + threadId + ".jsonl");
			Files.writeString(transcript, "{\"type\":\"session_meta\"}\n");

			String result = CodexGetHistorySecrets.getCodexGetHistoryAccess().
				execute(scope, Map.of(Platform.current().homeVariable(),
				home.toString()), new String[]{threadId, "analyze"}, InputStream.nullInputStream());

			requireThat(result, "result").contains("transcript=" + transcript.toAbsolutePath().normalize()).
				contains("records=1");
		}
	}

	/**
	 * Verifies an excluded thread identifier is rejected before transcript lookup.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Thread ID must be a UUID: not-a-uuid")
	public void rejectsInvalidRequestedThreadId() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope())
		{
			CodexGetHistorySecrets.getCodexGetHistoryAccess().
				execute(scope, Map.of("CODEX_HOME", "/unused"), new String[]{"not-a-uuid", "analyze"},
				InputStream.nullInputStream());
		}
	}

	/**
	 * Verifies that {@code search} on a thread forked at a past turn finds the parent's records up to the fork point and
	 * the fork's own records, and does not find a record that the parent wrote after the fork point.
	 */
	@Test
	public void searchReadsParentHistoryUpToForkPoint() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			writeForkedThread(codexHome);

			String inherited = history(scope, codexHome, "search", BEFORE_FORK);
			String own = history(scope, codexHome, "search", FORK_OWN);
			String later = history(scope, codexHome, "search", AFTER_FORK);

			requireThat(inherited, "inherited").contains("\"ordinal\":1,").contains(BEFORE_FORK);
			requireThat(own, "own").contains("\"ordinal\":4,").contains(FORK_OWN);
			requireThat(later, "later").doesNotContain(AFTER_FORK);
		}
	}

	/**
	 * Verifies that {@code analyze} on a thread forked at a past turn counts the parent's records up to the fork point
	 * and the fork's own records, but not the parent's records after the fork point.
	 */
	@Test
	public void analyzeCountsParentHistoryUpToForkPoint() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			writeForkedThread(codexHome);

			String result = history(scope, codexHome, "analyze");

			requireThat(result, "result").contains("records=5\n");
		}
	}

	/**
	 * Verifies that {@code summary} on a thread forked at a past turn counts the parent's records up to the fork point
	 * and the fork's own records, by type, but not the parent's records after the fork point, and reports the times of
	 * the first and last of them, which shows that the parent's records come first.
	 */
	@Test
	public void summaryCountsParentHistoryUpToForkPoint() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			writeForkedThread(codexHome);

			String result = history(scope, codexHome, "summary");

			requireThat(result, "result").contains("records=5\n").contains("types=event_msg:3,session_meta:2\n").
				contains("first_timestamp=2026-11-06T10:00:00Z\nlast_timestamp=2026-11-06T10:04:00Z\n");
		}
	}

	/**
	 * Verifies that a thread forked from a parent whose rollout is gone reports its own records and leaves out the
	 * inherited ones, without failing.
	 */
	@Test
	public void missingParentLeavesOwnHistory() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			writeForkedThread(codexHome);
			Files.delete(rolloutOf(codexHome, PARENT_ID));

			String result = history(scope, codexHome, "analyze");

			requireThat(result, "result").contains("records=2\n");
		}
	}

	/**
	 * Verifies that a thread forked from a parent whose rollout cannot be read reports its own records and leaves out
	 * the inherited ones, without failing.
	 */
	@Test
	public void unreadableParentLeavesOwnHistory() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			writeForkedThread(codexHome);
			byte[] notUtf8 = {(byte) 0xFF, (byte) 0xFE, '\n'};
			Files.write(rolloutOf(codexHome, PARENT_ID), notUtf8);

			String result = history(scope, codexHome, "analyze");

			requireThat(result, "result").contains("records=2\n");
		}
	}

	/**
	 * Verifies that a thread forked from a readable parent whose own rollout cannot be read fails, rather than reporting
	 * the parent's records as if they were its whole history.
	 *
	 * @throws IOException if a fixture cannot be written
	 */
	@Test
	public void unreadableOwnRolloutFails() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			writeForkedThread(codexHome);
			Path own = rolloutOf(codexHome, FORK_ID);
			// Long enough that the failure comes after the first block of the rollout, which is read to find its parent
			Files.writeString(own, record(5, "event_msg", message("x".repeat(20_000))) + "\n",
				StandardOpenOption.APPEND);
			byte[] notUtf8 = {(byte) 0xFF, (byte) 0xFE, '\n'};
			Files.write(own, notUtf8, StandardOpenOption.APPEND);

			for (String operation : List.of("analyze", "summary"))
			{
				try
				{
					history(scope, codexHome, operation);
					throw new AssertionError(operation + " reported a history that cannot be read");
				}
				catch (IOException _)
				{
					// expected
				}
			}
		}
	}

	/**
	 * Writes a parent thread that sent a message before and after a fork point, and a thread forked at that point
	 * that sent a message of its own. The parent's rollout holds ordinals 0 to 3 and the fork inherits those before 3;
	 * the fork's own rollout starts at ordinal 3.
	 *
	 * @param codexHome the Codex home
	 * @throws IOException if a rollout cannot be written
	 */
	private static void writeForkedThread(Path codexHome) throws IOException
	{
		Files.writeString(rolloutOf(codexHome, PARENT_ID), String.join("\n",
			record(0, "session_meta", "\"id\":\"" + PARENT_ID + "\""),
			record(1, "event_msg", message(BEFORE_FORK)),
			record(2, "event_msg", message("another message before the fork")),
			record(3, "event_msg", message(AFTER_FORK))) + "\n");
		Files.writeString(rolloutOf(codexHome, FORK_ID), String.join("\n",
			record(3, "session_meta", "\"id\":\"" + FORK_ID + "\",\"history_base\":{\"thread_id\":\"" + PARENT_ID +
				"\",\"end_ordinal_exclusive\":3}"),
			record(4, "event_msg", message(FORK_OWN))) + "\n");
	}

	/**
	 * Returns where Codex keeps a thread's rollout, creating its folder.
	 *
	 * @param codexHome the Codex home
	 * @param threadId the thread's identifier
	 * @return the rollout file, which may not exist
	 * @throws IOException if its folder cannot be created
	 */
	private static Path rolloutOf(Path codexHome, String threadId) throws IOException
	{
		return Files.createDirectories(codexHome.resolve("sessions/2026/11/06")).resolve(
			"rollout-2026-11-06T10-00-00-" + threadId + ".jsonl");
	}

	/**
	 * Returns a rollout record as Codex numbers its records.
	 *
	 * @param ordinal the record's ordinal
	 * @param type the record's type
	 * @param payload the members of the record's payload, as JSON text
	 * @return the record's line
	 */
	private static String record(int ordinal, String type, String payload)
	{
		return "{\"timestamp\":\"2026-11-06T10:0" + ordinal + ":00Z\",\"ordinal\":" + ordinal + ",\"type\":\"" + type +
			"\",\"payload\":{" + payload + "}}";
	}

	/**
	 * Returns the payload of a message record.
	 *
	 * @param text the message
	 * @return the payload members, as JSON text
	 */
	private static String message(String text)
	{
		return "\"type\":\"agent_message\",\"message\":\"" + text + "\"";
	}

	/**
	 * Runs a {@code get-history} operation on {@link #FORK_ID}.
	 *
	 * @param scope the process scope
	 * @param codexHome the Codex home
	 * @param operation the operation and its arguments
	 * @return the command's result
	 * @throws IOException if the command fails
	 */
	private static String history(TestJvmScope scope, Path codexHome, String... operation) throws IOException
	{
		String[] args = new String[operation.length + 1];
		args[0] = FORK_ID;
		System.arraycopy(operation, 0, args, 1, operation.length);
		return CodexGetHistorySecrets.getCodexGetHistoryAccess().execute(scope, Map.of("CODEX_HOME", codexHome.toString()),
			args, InputStream.nullInputStream());
	}
}
