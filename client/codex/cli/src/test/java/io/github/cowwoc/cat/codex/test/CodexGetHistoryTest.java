package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.CodexGetHistory;
import io.github.cowwoc.cat.codex.secrets.CodexGetHistorySecrets;
import io.github.cowwoc.cat.common.Platform;
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
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path codexHome = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			String threadId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(codexHome.resolve("sessions/2026/08/14")).resolve(
				"rollout-2026-08-14T01-00-00-" + threadId + ".jsonl");
			Files.writeString(transcript, "{\"type\":\"session_meta\"}\n");

			String result = CodexGetHistorySecrets.access().execute(Map.of("CODEX_HOME", codexHome.toString()),
				new String[]{threadId, "analyze"});

			requireThat(result, "result").contains("transcript=" + transcript.toAbsolutePath().normalize()).
				contains("records=1");
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-history-"));
			String threadId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(home.resolve(".codex/sessions/2026/08/14")).resolve(
				"rollout-2026-08-14T01-00-00-" + threadId + ".jsonl");
			Files.writeString(transcript, "{\"type\":\"session_meta\"}\n");

			String result = CodexGetHistorySecrets.access().execute(Map.of(Platform.current().homeVariable(),
				home.toString()), new String[]{threadId, "analyze"});

			requireThat(result, "result").contains("transcript=" + transcript.toAbsolutePath().normalize()).
				contains("records=1");
		}
	}

	/**
	 * Verifies the command names both variables when neither {@code CODEX_HOME} nor {@code HOME} locates the Codex home.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*CODEX_HOME.*HOME.*")
	public void rejectsMissingCodexHomeAndHome() throws IOException
	{
		// Requirement: R-I5
		CodexGetHistorySecrets.access().execute(Map.of(), new String[]{UUID.randomUUID().toString(), "analyze"});
	}

	/**
	 * Verifies an excluded thread identifier is rejected before transcript lookup.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Thread ID must be a UUID: not-a-uuid")
	public void rejectsInvalidRequestedThreadId() throws IOException
	{
		CodexGetHistorySecrets.access().execute(Map.of("CODEX_HOME", "/unused"), new String[]{"not-a-uuid", "analyze"});
	}
}
