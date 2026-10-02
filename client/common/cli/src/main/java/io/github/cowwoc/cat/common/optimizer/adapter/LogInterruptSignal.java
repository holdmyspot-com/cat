package io.github.cowwoc.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reports a user interrupt that the host harness recorded in the host session's JSONL log.
 * <p>
 * The signal reads the records that the log gained since the previous poll. A subclass recognizes its harness's
 * interrupt record. A record is dated by its {@code timestamp}, or by the clock if it has none.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public abstract class LogInterruptSignal implements HostInterruptSignal
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final LineTail tail;
	private final Clock clock;
	private final String marker;
	private final Object lock = new Object();
	private Instant latest;

	/**
	 * Creates a signal that reads a log.
	 *
	 * @param log the host session's log. It need not exist yet.
	 * @param clock the clock that dates a record that carries no timestamp
	 * @param marker text that every interrupt record contains. A line without it is not parsed.
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code marker} is empty
	 */
	protected LogInterruptSignal(Path log, Clock clock, String marker)
	{
		requireThat(log, "log").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(marker, "marker").isNotEmpty();
		this.tail = new LineTail(log);
		this.clock = clock;
		this.marker = marker;
	}

	@Override
	public final boolean isInterruptedAfter(Instant start) throws IOException
	{
		synchronized (lock)
		{
			requireThat(start, "start").isNotNull();
			for (String line : tail.readNewLines())
			{
				if (!line.contains(marker))
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
	 * Indicates whether a log record is a user interrupt.
	 *
	 * @param record a record whose line contains the marker
	 * @return {@code true} if the record is a user interrupt
	 */
	protected abstract boolean isInterrupt(JsonNode record);

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
