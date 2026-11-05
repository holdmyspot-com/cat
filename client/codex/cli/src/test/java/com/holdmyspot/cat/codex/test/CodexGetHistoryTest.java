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
}
