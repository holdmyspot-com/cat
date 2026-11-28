package com.holdmyspot.cat.common.history.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.history.SessionHistory;
import com.holdmyspot.cat.common.secrets.SessionHistorySecrets;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.core.JacksonException;

/**
 * Tests {@link SessionHistory} command behavior.
 */
public final class SessionHistoryTest
{
	/**
	 * Creates the test fixture.
	 */
	public SessionHistoryTest()
	{
	}

	/**
	 * Verifies a search returns matching JSONL records with the requested neighboring records.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void searchesJsonlWithContext() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-session-history-")).resolve("session.jsonl");
			Files.writeString(transcript, """
				{"type":"message","text":"distant-early"}
				{"type":"message","text":"before"}
				{"type":"message","text":"needle"}
				{"type":"message","text":"after"}
				{"type":"message","text":"distant-late"}
				""");

			String result = SessionHistorySecrets.getSessionHistoryAccess().search(scope, transcript, "needle", 1);

			requireThat(result, "result").contains("before").contains("needle").contains("after").
				doesNotContain("distant-early").doesNotContain("distant-late");
		}
	}

	/**
	 * Verifies a bounded summary reports selected event metadata without returning transcript content.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void summarizesBoundedEventMetadata() throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-session-history-")).resolve("session.jsonl");
			Files.writeString(transcript, """
				{"type":"message","timestamp":"2026-08-18T10:00:00Z","text":"before"}
				{"type":"message","timestamp":"2026-08-18T10:01:00Z","text":"secret detail"}
				{"type":"tool","timestamp":"2026-08-18T10:02:00Z","text":"after"}
				""");

			String result = SessionHistorySecrets.getSessionHistoryAccess().
				execute(scope, new String[]{"summary", transcript.toString(), "--since",
				"2026-08-18T10:00:30Z", "--type", "message", "--limit", "1"});

			requireThat(result, "result").contains("records=1").contains("types=message:1").
				contains("first_timestamp=2026-08-18T10:01:00Z").
				doesNotContain("secret detail").doesNotContain("after");
		}
	}

	/**
	 * Returns the history queries that parse transcript records.
	 *
	 * @return each operation and its arguments
	 */
	@DataProvider
	public static Object[][] historyOperations()
	{
		return new Object[][]
		{
			{new String[]{"analyze"}},
			{new String[]{"summary"}},
			{new String[]{"search", "needle"}},
			{new String[]{"errors"}},
			{new String[]{"file-history", "sample.txt"}}
		};
	}

	/**
	 * Verifies that malformed JSON reports its file and physical line, counting preceding blank lines, and retains
	 * the parser failure as its cause.
	 *
	 * @param operation the query and its arguments
	 * @throws IOException if the transcript cannot be read or contains malformed JSON
	 */
	@Test(dataProvider = "historyOperations", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*session\\.jsonl:3.*")
	public void reportsMalformedJsonLocation(String[] operation) throws IOException
	{
		try (TestJvmScope scope = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-session-history-")).resolve("session.jsonl");
			Files.writeString(transcript, "\n \n{\n");
			try
			{
				SessionHistory.execute(scope, transcript, operation);
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(transcript + ":3");
				requireThat(e.getCause(), "cause").isInstanceOf(JacksonException.class);
				throw e;
			}
		}
	}
}
