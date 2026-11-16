package com.holdmyspot.cat.codex.optimizer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.holdmyspot.cat.common.history.TranscriptLines;
import com.holdmyspot.cat.common.history.TranscriptLines.Line;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads retained Codex rollout records with their physical source locations.
 */
final class RolloutRecords
{
	/**
	 * Prevents instantiation.
	 */
	private RolloutRecords()
	{
	}

	/**
	 * Reads each physical line of a rollout lazily and unwraps I/O failures raised while consuming the stream.
	 *
	 * @param file the rollout file
	 * @param consumer receives each line and may stop reading by throwing an I/O failure
	 * @throws IOException if the rollout cannot be read
	 */
	static void read(Path file, RecordConsumer consumer) throws IOException
	{
		try (Stream<Line> lines = TranscriptLines.readFileLines(file))
		{
			lines.forEach(line ->
			{
				try
				{
					consumer.accept(line);
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
		}
		catch (UncheckedIOException e)
		{
			throw e.getCause();
		}
	}

	/**
	 * Parses one rollout line, reporting its source location when its contents are not JSON.
	 *
	 * @param json the JSON mapper
	 * @param line the rollout line and its source location
	 * @return the line's JSON value, or a missing node for a blank line
	 * @throws IOException if the line is malformed JSON
	 */
	static JsonNode parse(JsonMapper json, Line line) throws IOException
	{
		if (line.text().isBlank())
			return json.missingNode();
		try
		{
			return json.readTree(line.text());
		}
		catch (JacksonException e)
		{
			throw new IOException("Malformed JSON in " + line.transcript().toAbsolutePath() + ":" + line.lineNumber() +
				": " + e.getMessage(), e);
		}
	}

	/**
	 * Receives one rollout line and may report an I/O failure while processing it.
	 */
	@FunctionalInterface
	interface RecordConsumer
	{
		/**
		 * Processes one rollout line.
		 *
		 * @param line the rollout line
		 * @throws IOException if processing the line fails
		 */
		void accept(Line line) throws IOException;
	}
}
