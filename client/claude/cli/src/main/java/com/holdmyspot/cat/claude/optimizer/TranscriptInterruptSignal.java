package com.holdmyspot.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.ClaudeConfiguration;

import com.holdmyspot.cat.common.optimizer.adapter.LogInterruptSignal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * Reports a user interrupt that Claude Code recorded in the host session's transcript.
 * <p>
 * The transcript is {@code <configuration directory>/projects/<project>/<session>.jsonl}. Claude Code records an
 * interrupt as a user message whose text starts with {@value #PREFIX}. The wording after the prefix differs between
 * an interrupted turn and an interrupted tool use, so only the prefix is matched.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class TranscriptInterruptSignal extends LogInterruptSignal
{
	private static final String PREFIX = "[Request interrupted by user";

	/**
	 * Creates a signal that reads a transcript.
	 *
	 * @param transcript the host session's transcript. It need not exist yet.
	 * @param clock the clock that dates a record that carries no timestamp
	 * @throws NullPointerException if any argument is null
	 */
	public TranscriptInterruptSignal(Path transcript, Clock clock)
	{
		super(transcript, clock, PREFIX);
	}

	/**
	 * Finds the transcript of a session.
	 *
	 * @param configurationDirectory the directory returned by
	 *   {@link ClaudeConfiguration#resolveDirectory(Path, String)}
	 * @param sessionId the host session's identifier, from {@code CLAUDE_CODE_SESSION_ID}
	 * @return the transcript, or empty if no project directory holds one for the session
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code sessionId} is blank or contains a path separator
	 * @throws IOException if the project directories cannot be listed
	 */
	public static Optional<Path> locate(Path configurationDirectory, String sessionId) throws IOException
	{
		requireThat(configurationDirectory, "configurationDirectory").isNotNull();
		requireThat(sessionId, "sessionId").isNotBlank();
		requireThat(sessionId.contains("/") || sessionId.contains("\\"), "sessionId.hasSeparator").isFalse();
		Path projects = configurationDirectory.resolve("projects");
		if (!Files.isDirectory(projects))
			return Optional.empty();
		try (DirectoryStream<Path> directories = Files.newDirectoryStream(projects))
		{
			for (Path directory : directories)
			{
				Path transcript = directory.resolve(sessionId + ".jsonl");
				if (Files.isRegularFile(transcript))
					return Optional.of(transcript);
			}
		}
		return Optional.empty();
	}

	/**
	 * Indicates whether a transcript record is a user interrupt.
	 *
	 * @param record the record
	 * @return {@code true} if the record is a user message whose text starts with {@value #PREFIX}
	 */
	@Override
	protected boolean isInterrupt(JsonNode record)
	{
		if (!"user".equals(record.path("type").asString("")))
			return false;
		JsonNode content = record.path("message").path("content");
		if (content.isString())
			return content.stringValue().startsWith(PREFIX);
		if (content.isArray() && !content.isEmpty())
		{
			JsonNode first = content.get(0);
			return "text".equals(first.path("type").asString("")) &&
				first.path("text").asString("").startsWith(PREFIX);
		}
		return false;
	}
}
