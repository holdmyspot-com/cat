package io.github.cowwoc.cat.common.history.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.history.SessionHistory;
import io.github.cowwoc.cat.common.secrets.SessionHistorySecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-session-history-")).resolve("session.jsonl");
			Files.writeString(transcript, """
				{"type":"message","text":"before"}
				{"type":"message","text":"needle"}
				{"type":"message","text":"after"}
				""");

			String result = SessionHistorySecrets.access().search(transcript, "needle", 1);

			requireThat(result, "result").contains("before").contains("needle").contains("after");
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-session-history-")).resolve("session.jsonl");
			Files.writeString(transcript, """
				{"type":"message","timestamp":"2026-08-18T10:00:00Z","text":"before"}
				{"type":"message","timestamp":"2026-08-18T10:01:00Z","text":"secret detail"}
				{"type":"tool","timestamp":"2026-08-18T10:02:00Z","text":"after"}
				""");

			String result = SessionHistorySecrets.access().execute(new String[]{"summary", transcript.toString(), "--since",
				"2026-08-18T10:00:30Z", "--type", "message", "--limit", "1"});

			requireThat(result, "result").contains("records=1").contains("types=message:1").
				contains("first_timestamp=2026-08-18T10:01:00Z").
				doesNotContain("secret detail").doesNotContain("after");
		}
	}
}
