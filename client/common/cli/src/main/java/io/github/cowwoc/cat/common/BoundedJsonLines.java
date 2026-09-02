package io.github.cowwoc.cat.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Streams newline-terminated JSON records without allowing one record to grow without a bound.
 */
public final class BoundedJsonLines
{
	/**
	 * Prevents construction of this static reader.
	 */
	private BoundedJsonLines()
	{
	}

	/**
	 * Reads every complete line while bounding the memory used by one record.
	 *
	 * <p>The JDK's line-streaming helpers do not cap an individual line before allocating it. This reader enforces the
	 * schema bound while characters are consumed and rejects an unterminated final append as incomplete durable
	 * evidence.</p>
	 *
	 * @param path the JSON Lines file
	 * @param maximumRecordCharacters the maximum characters in one record
	 * @param description the artifact name used in diagnostics
	 * @param consumer the record consumer
	 * @throws IOException if a record exceeds the bound, the last append is incomplete, or the consumer rejects it
	 */
	public static void read(Path path, int maximumRecordCharacters, String description, RecordConsumer consumer)
		throws IOException
	{
		if (path == null)
			throw new NullPointerException("path must not be null");
		if (maximumRecordCharacters < 1)
			throw new IllegalArgumentException("maximumRecordCharacters must be positive");
		if (description == null || description.isBlank())
			throw new IllegalArgumentException("description must not be blank");
		if (consumer == null)
			throw new NullPointerException("consumer must not be null");

		StringBuilder record = new StringBuilder(Math.min(maximumRecordCharacters, 1024));
		int lineNumber = 1;
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			for (int character = reader.read(); character != -1; character = reader.read())
			{
				if (character == '\n')
				{
					if (!record.isEmpty() && record.charAt(record.length() - 1) == '\r')
						record.setLength(record.length() - 1);
					consumer.accept(record.toString(), lineNumber);
					record.setLength(0);
					++lineNumber;
					continue;
				}
				record.append((char) character);
				if (record.length() > maximumRecordCharacters)
				{
					throw new IOException(description + " contains a record longer than " + maximumRecordCharacters +
						" characters: " + path + " (line " + lineNumber + ")");
				}
			}
		}
		if (!record.isEmpty())
			throw new IOException(description + " ends with an incomplete record: " + path);
	}

	/**
	 * Consumes one bounded record.
	 */
	@FunctionalInterface
	public interface RecordConsumer
	{
		/**
		 * Consumes one record.
		 *
		 * @param record the complete record without its newline
		 * @param lineNumber the one-based source line number
		 * @throws IOException if the record violates its domain schema
		 */
		void accept(String record, int lineNumber) throws IOException;
	}
}
