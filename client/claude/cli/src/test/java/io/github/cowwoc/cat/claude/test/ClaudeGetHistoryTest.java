package io.github.cowwoc.cat.claude.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.ClaudeGetHistory;
import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeGetHistory}.
 */
public final class ClaudeGetHistoryTest
{
	/**
	 * Verifies an explicit Claude session identifier selects its main transcript.
	 */
	@Test
	public void resolvesRequestedMainTranscript() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-history-"));
			String sessionId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(configuration.resolve("projects/-workspace")).resolve(sessionId +
				".jsonl");
			Files.writeString(transcript, "{\"type\":\"user\"}\n");

			String result = ClaudeGetHistory.execute(Map.of("CLAUDE_CONFIG_DIR", configuration.toString()),
				new String[]{sessionId, "analyze"});

			requireThat(result, "result").contains("transcript=" + transcript.toAbsolutePath().normalize()).
				contains("records=1");
		}
	}

	/**
	 * Verifies an explicit agent identifier selects that agent's transcript within the requested Claude session.
	 */
	@Test
	public void resolvesRequestedAgentTranscript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-history-"));
			String sessionId = UUID.randomUUID().toString();
			String agentId = "agent-123";
			Path project = Files.createDirectories(configuration.resolve("projects/-workspace"));
			Files.writeString(project.resolve(sessionId + ".jsonl"), "{\"type\":\"user\"}\n");
			Path transcript = Files.createDirectories(project.resolve(sessionId + "/subagents")).resolve(
				"agent-" + agentId + ".jsonl");
			Files.writeString(transcript, "{\"type\":\"assistant\"}\n");

			String result = ClaudeGetHistory.execute(Map.of("CLAUDE_CONFIG_DIR", configuration.toString()),
				new String[]{sessionId, "--agent", agentId, "analyze"});

			requireThat(result, "result").contains("transcript=" + transcript.toAbsolutePath().normalize()).
				contains("records=1");
		}
	}

	/**
	 * Verifies that without {@code CLAUDE_CONFIG_DIR} the transcript is read from {@code .claude} below the user's home
	 * directory ({@code HOME}, or {@code USERPROFILE} on Windows), the environment of an ordinary Claude Code tool shell.
	 */
	@Test
	public void readsTranscriptBelowHomeByDefault() throws IOException
	{
		// Requirement: R-I5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-history-"));
			String sessionId = UUID.randomUUID().toString();
			Path transcript = Files.createDirectories(home.resolve(".claude/projects/-workspace")).resolve(sessionId +
				".jsonl");
			Files.writeString(transcript, "{\"type\":\"user\"}\n");

			String result = ClaudeGetHistory.execute(Map.of(Platform.current().homeVariable(), home.toString()),
				new String[]{sessionId, "analyze"});

			requireThat(result, "result").contains("transcript=" + transcript.toAbsolutePath().normalize()).
				contains("records=1");
		}
	}

	/**
	 * Verifies the command names both variables when neither {@code CLAUDE_CONFIG_DIR} nor the variable that names the
	 * user's home directory locates the Claude Code configuration directory.
	 */
	@Test
	public void rejectsMissingConfigDirectoryAndHome()
	{
		// Requirement: R-I5
		try
		{
			ClaudeGetHistory.execute(Map.of(), new String[]{UUID.randomUUID().toString(), "analyze"});
			throw new AssertionError("The command accepted an environment without a configuration directory");
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").contains("CLAUDE_CONFIG_DIR").
				contains(Platform.current().homeVariable());
		}
	}

	/**
	 * Verifies an excluded agent identifier is rejected before transcript lookup.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Agent ID must contain only letters, digits, and hyphens: invalid/agent")
	public void rejectsInvalidRequestedAgentId() throws IOException
	{
		ClaudeGetHistory.execute(Map.of("CLAUDE_CONFIG_DIR", "/unused"),
			new String[]{UUID.randomUUID().toString(), "--agent", "invalid/agent", "analyze"});
	}
}
