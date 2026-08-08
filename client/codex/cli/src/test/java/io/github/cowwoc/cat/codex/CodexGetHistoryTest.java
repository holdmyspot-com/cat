package io.github.cowwoc.cat.codex;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexGetHistory}.
 */
public final class CodexGetHistoryTest
{
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			String threadId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(codexHome.resolve("sessions/2026/08/14")).resolve(
				"rollout-2026-08-14T01-00-00-" + threadId + ".jsonl");
			Files.writeString(transcript, "{\"type\":\"session_meta\"}\n");

			String result = CodexGetHistory.execute(Map.of("CODEX_HOME", codexHome.toString()),
				new String[]{threadId, "analyze"});

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
		CodexGetHistory.execute(Map.of("CODEX_HOME", "/unused"), new String[]{"not-a-uuid", "analyze"});
	}
}
