package io.github.cowwoc.cat.claude;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Selects the model attached to the exact parent tool use in Claude's native transcript.
 */
final class HostToolModel
{
	private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder().
		enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()).build();
	private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Reads streamed records and selects the exact assistant parent record.
	 * Malformed final input can remain pending while the host is still writing it.
	 *
	 * @param records caller-owned UTF-8 record reader
	 * @param session exact native session identity
	 * @param toolUse exact tool-use identity
	 * @param lastRecordComplete whether the final record is known complete
	 * @return the unique model associated with the parent tool use
	 * @throws IOException if a complete record is malformed or matching records disagree
	 */
	static Optional<String> findModel(BufferedReader records, String session, String toolUse,
		boolean lastRecordComplete) throws IOException
	{
		Objects.requireNonNull(records, "records");
		requireIdentity(session, "session");
		requireIdentity(toolUse, "toolUse");
		String record = records.readLine();
		String matchedModel = null;
		while (record != null)
		{
			String next = records.readLine();
			boolean isFinal = next == null;
			try
			{
				JsonNode event = JSON.readTree(record);
				String model = matchingModel(event, session, toolUse);
				if (model != null)
				{
					if (matchedModel != null && !matchedModel.equals(model))
						throw new IOException("Claude transcript has conflicting models for the parent tool use");
					matchedModel = model;
				}
			}
			catch (JacksonException e)
			{
				if (!isFinal || lastRecordComplete)
					throw new IOException("Claude transcript contains a malformed complete record", e);
			}
			record = next;
		}
		return Optional.ofNullable(matchedModel);
	}

	/**
	 * Waits briefly for the native transcript to contain the current parent tool use.
	 *
	 * @param transcript native transcript path
	 * @param session exact native session identity
	 * @param toolUse exact tool-use identity
	 * @param clock deadline clock
	 * @param pause injected pause operation
	 * @return exact observed native model
	 * @throws IOException if lookup fails or times out
	 */
	static String awaitModel(Path transcript, String session, String toolUse, Clock clock, Pause pause)
		throws IOException
	{
		Objects.requireNonNull(transcript, "transcript");
		requireIdentity(session, "session");
		requireIdentity(toolUse, "toolUse");
		Objects.requireNonNull(clock, "clock");
		Objects.requireNonNull(pause, "pause");
		Instant deadline = clock.instant().plus(TIMEOUT);
		while (true)
		{
			try
			{
				try (SeekableByteChannel channel = Files.newByteChannel(transcript, StandardOpenOption.READ))
				{
					Optional<String> model = readAttempt(channel, session, toolUse);
					if (model.isPresent())
						return model.get();
				}
			}
			catch (NoSuchFileException _)
			{
				// A transcript may be created after the hook starts.
			}
			Instant now = clock.instant();
			if (!now.isBefore(deadline))
				throw new IOException("Timed out waiting for Claude transcript model for tool use " + toolUse);
			Duration remaining = Duration.between(now, deadline);
			try
			{
				Duration delay = POLL_INTERVAL;
				if (remaining.compareTo(POLL_INTERVAL) < 0)
					delay = remaining;
				pause.await(delay);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for Claude transcript model", e);
			}
		}
	}

	/**
	 * Reads only the extent captured from one open transcript channel.
	 *
	 * @param channel caller-owned seekable transcript channel
	 * @param session exact native session identity
	 * @param toolUse exact native tool-use identity
	 * @return a matching model when present in the captured bytes
	 * @throws IOException if the captured complete records are malformed
	 */
	static Optional<String> readAttempt(SeekableByteChannel channel, String session, String toolUse)
		throws IOException
	{
		Objects.requireNonNull(channel, "channel");
		requireIdentity(session, "session");
		requireIdentity(toolUse, "toolUse");
		return readAttempt(channel, session, toolUse, channel.size());
	}

	/**
	 * Reads an explicitly captured transcript extent from its still-open channel.
	 *
	 * @param channel caller-owned seekable transcript channel
	 * @param session exact native session identity
	 * @param toolUse exact native tool-use identity
	 * @param size captured byte extent
	 * @return a matching model when present in the captured bytes
	 * @throws IOException if the captured complete records are malformed
	 */
	static Optional<String> readAttempt(SeekableByteChannel channel, String session, String toolUse, long size)
		throws IOException
	{
		Objects.requireNonNull(channel, "channel");
		requireIdentity(session, "session");
		requireIdentity(toolUse, "toolUse");
		if (size < 0)
			throw new IllegalArgumentException("size must not be negative");
		boolean complete = size > 0 && lastByteIsNewline(channel, size);
		channel.position(0);
		BoundedInputStream input = new BoundedInputStream(Channels.newInputStream(channel), size);
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF_8.newDecoder())))
		{
			return findModel(reader, session, toolUse, complete);
		}
	}

	/**
	 * Checks whether the final byte is a record delimiter.
	 *
	 * @param channel open transcript channel
	 * @param size channel size
	 * @return whether the final byte is newline
	 * @throws IOException if the channel cannot be read
	 */
	private static boolean lastByteIsNewline(SeekableByteChannel channel, long size) throws IOException
	{
		channel.position(size - 1);
		ByteBuffer last = ByteBuffer.allocate(1);
		channel.read(last);
		return last.array()[0] == '\n';
	}

	/**
	 * Limits a stream to bytes included in one channel-size snapshot.
	 */
	private static final class BoundedInputStream extends FilterInputStream
	{
		private long remaining;

		/**
		 * Creates a non-closing bounded view of the caller-owned channel stream.
		 *
		 * @param input channel input stream
		 * @param size captured byte extent
		 */
		BoundedInputStream(InputStream input, long size)
		{
			super(input);
			remaining = size;
		}

		@Override
		public int read() throws IOException
		{
			if (remaining == 0)
				return -1;
			int value = in.read();
			if (value != -1)
				--remaining;
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException
		{
			if (length == 0)
				return 0;
			if (remaining == 0)
				return -1;
			int limitedLength = (int) Math.min(length, remaining);
			int count = in.read(bytes, offset, limitedLength);
			if (count > 0)
				remaining -= count;
			return count;
		}

		@Override
		public void close()
		{
			// The caller owns the channel and closes it after this attempt.
		}
	}

	/**
	 * Selects model only when all native ownership fields and the tool id match.
	 *
	 * @param event parsed native record
	 * @param session requested session
	 * @param toolUse requested tool use
	 * @return matching model, or null for an unrelated record
	 * @throws IOException if a matching record lacks a usable model
	 */
	private static String matchingModel(JsonNode event, String session, String toolUse) throws IOException
	{
		if (event == null || !event.isObject() || !"assistant".equals(text(event, "type")) ||
			!session.equals(text(event, "sessionId")))
			return null;
		JsonNode sidechain = event.path("isSidechain");
		if (!sidechain.isBoolean() || sidechain.booleanValue())
			return null;
		JsonNode message = event.path("message");
		if (!message.isObject())
			return null;
		JsonNode content = message.path("content");
		if (!content.isArray())
			return null;
		boolean found = false;
		for (JsonNode item: content)
			if (item.isObject() && "tool_use".equals(text(item, "type")) && toolUse.equals(text(item, "id")))
				found = true;
		if (!found)
			return null;
		String model = text(message, "model");
		if (model == null || model.isBlank())
			throw new IOException("Claude parent tool-use record is missing a nonblank model");
		return model;
	}

	/**
	 * Reads text without coercing JSON values.
	 *
	 * @param node record object
	 * @param field field name
	 * @return exact text, or null for nontext values
	 */
	private static String text(JsonNode node, String field)
	{
		JsonNode value = node.path(field);
		if (!value.isString())
			return null;
		return value.asString();
	}

	/**
	 * Validates an exact identity argument.
	 *
	 * @param value identity value
	 * @param name argument name
	 */
	private static void requireIdentity(String value, String name)
	{
		Objects.requireNonNull(value, name);
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
	}

	@FunctionalInterface
	interface Pause
	{
		/**
		 * Waits for the requested polling interval.
		 *
		 * @param delay requested duration
		 * @throws InterruptedException if waiting is interrupted
		 */
		void await(Duration delay) throws InterruptedException;
	}

	@FunctionalInterface
	interface Lookup
	{
		/**
		 * Reads a model for one exact native tool identity.
		 *
		 * @param transcript transcript path
		 * @param session native session identity
		 * @param toolUse native tool-use identity
		 * @return model observed in the parent record
		 * @throws IOException if lookup fails
		 */
		String read(Path transcript, String session, String toolUse) throws IOException;
	}

	/**
	 * Prevents construction.
	 */
	private HostToolModel()
	{
	}
}
