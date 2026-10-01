package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports a user interrupt that Codex recorded in the host thread's rollout (design &sect;3.8, H11).
 * <p>
 * The rollout is {@code $CODEX_HOME/sessions/**}{@code /rollout-*-$CODEX_THREAD_ID.jsonl}. Codex records an
 * interrupt as an {@code event_msg} record whose payload type is {@code turn_aborted}. The conversation item that
 * quotes the same word does not count.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RolloutInterruptSignal implements HostInterruptSignal
{
	private static final String EVENT = "turn_aborted";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final LineTail tail;
	private final Clock clock;
	private Instant latest;
	private final Object lock = new Object();

	/**
	 * Creates a signal that reads a rollout.
	 *
	 * @param rollout the host thread's rollout. It need not exist yet.
	 * @param clock the clock that dates a record that carries no timestamp
	 * @throws NullPointerException if any argument is null
	 */
	public RolloutInterruptSignal(Path rollout, Clock clock)
	{
		requireThat(rollout, "rollout").isNotNull();
		requireThat(clock, "clock").isNotNull();
		this.tail = new LineTail(rollout);
		this.clock = clock;
	}

	/**
	 * Returns the directory that holds Codex's configuration and rollouts.
	 *
	 * @param home the user's home directory
	 * @param configured the value of {@code CODEX_HOME}, or {@code null} or blank if it is not set. It defaults to
	 *   {@code .codex} below {@code home}.
	 * @return the Codex home directory
	 * @throws NullPointerException if {@code home} is null
	 */
	public static Path codexHome(Path home, String configured)
	{
		requireThat(home, "home").isNotNull();
		if (configured == null || configured.isBlank())
			return home.resolve(".codex");
		return Path.of(configured);
	}

	/**
	 * Finds the rollout of a thread.
	 *
	 * @param codexHome the directory that {@link #codexHome(Path, String)} returned
	 * @param threadId the host thread's identifier, from {@code CODEX_THREAD_ID}
	 * @return the rollout, or empty if none exists for the thread. If several do, the one whose name sorts last,
	 *   which is the most recently started.
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code threadId} is blank or contains a path separator
	 * @throws IOException if the sessions directory cannot be listed
	 */
	public static Optional<Path> locate(Path codexHome, String threadId) throws IOException
	{
		requireThat(codexHome, "codexHome").isNotNull();
		requireThat(threadId, "threadId").isNotBlank();
		requireThat(threadId.contains("/") || threadId.contains("\\"), "threadId.hasSeparator").isFalse();
		Path sessions = codexHome.resolve("sessions");
		if (!Files.isDirectory(sessions))
			return Optional.empty();
		String suffix = "-" + threadId + ".jsonl";
		try (Stream<Path> files = Files.walk(sessions))
		{
			return files.filter(Files::isRegularFile).
				filter(file ->
				{
					String name = file.getFileName().toString();
					return name.startsWith("rollout-") && name.endsWith(suffix);
				}).
				max(Comparator.comparing(file -> file.getFileName().toString()));
		}
	}

	@Override
	public boolean isInterruptedAfter(Instant start) throws IOException
	{
		synchronized (lock)
		{
			requireThat(start, "start").isNotNull();
			for (String line : tail.readNewLines())
			{
				if (!line.contains(EVENT))
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
				if (!"event_msg".equals(record.path("type").asString("")) ||
					!EVENT.equals(record.path("payload").path("type").asString("")))
				{
					continue;
				}
				Instant recorded = timestamp(record);
				if (latest == null || recorded.isAfter(latest))
					latest = recorded;
			}
			return latest != null && latest.isAfter(start);
		}
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
