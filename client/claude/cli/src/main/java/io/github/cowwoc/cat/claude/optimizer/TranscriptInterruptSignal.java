package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports a user interrupt that Claude Code recorded in the host session's transcript (design &sect;3.8, H13).
 * <p>
 * The transcript is {@code <configuration directory>/projects/<project>/<session>.jsonl}. Claude Code records an
 * interrupt as a user message whose text starts with {@value #PREFIX}. The wording after the prefix differs between
 * an interrupted turn and an interrupted tool use, so only the prefix is matched.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class TranscriptInterruptSignal implements HostInterruptSignal
{
	private static final String PREFIX = "[Request interrupted by user";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final LineTail tail;
	private final Clock clock;
	private Instant latest;
	private final Object lock = new Object();

	/**
	 * Creates a signal that reads a transcript.
	 *
	 * @param transcript the host session's transcript. It need not exist yet.
	 * @param clock the clock that dates a record that carries no timestamp
	 * @throws NullPointerException if any argument is null
	 */
	public TranscriptInterruptSignal(Path transcript, Clock clock)
	{
		requireThat(transcript, "transcript").isNotNull();
		requireThat(clock, "clock").isNotNull();
		this.tail = new LineTail(transcript);
		this.clock = clock;
	}

	/**
	 * Returns the directory that holds Claude Code's configuration and session transcripts.
	 *
	 * @param home the user's home directory
	 * @param configured the value of {@code CLAUDE_CONFIG_DIR}, or {@code null} or blank if it is not set. Claude
	 *   Code's tool commands do not always receive it, so it defaults to {@code .claude} below {@code home}.
	 * @return the configuration directory
	 * @throws NullPointerException if {@code home} is null
	 */
	public static Path configurationDirectory(Path home, String configured)
	{
		requireThat(home, "home").isNotNull();
		if (configured == null || configured.isBlank())
			return home.resolve(".claude");
		return Path.of(configured);
	}

	/**
	 * Finds the transcript of a session.
	 *
	 * @param configurationDirectory the directory that {@link #configurationDirectory(Path, String)} returned
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

	@Override
	public boolean isInterruptedAfter(Instant start) throws IOException
	{
		synchronized (lock)
		{
			requireThat(start, "start").isNotNull();
			for (String line : tail.readNewLines())
			{
				if (!line.contains(PREFIX))
					continue;
				JsonNode record;
				try
				{
					record = JSON.readTree(line);
				}
				catch (JacksonException _)
				{
					continue;
				}
				if (!isInterrupt(record))
					continue;
				Instant recorded = timestamp(record);
				if (latest == null || recorded.isAfter(latest))
					latest = recorded;
			}
			return latest != null && latest.isAfter(start);
		}
	}

	/**
	 * Indicates whether a transcript record is a user interrupt.
	 *
	 * @param record the record
	 * @return {@code true} if the record is a user message whose text starts with {@value #PREFIX}
	 */
	private static boolean isInterrupt(JsonNode record)
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

	/**
	 * Returns when a record was written.
	 *
	 * @param record the record
	 * @return the record's timestamp, or the current time if the record has none
	 */
	private Instant timestamp(JsonNode record)
	{
		JsonNode stamp = record.path("timestamp");
		if (stamp.isString())
		{
			try
			{
				return Instant.parse(stamp.stringValue());
			}
			catch (DateTimeParseException _)
			{
				return clock.instant();
			}
		}
		return clock.instant();
	}
}
