package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.Pause;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A conversation between a session and a session that it requested, held through the files of a folder that the
 * asking session's commands may write, because appended files work in every harness's sandbox and need no reader to
 * be present when a message is written.
 * <p>
 * The asking session appends its messages to {@value #TO_CHILD_FILE}, one JSON object per line:
 * {@code {"turn": "<text>"}} asks for the child's next turn, {@code {"interrupt": true}} interrupts the turn in
 * progress, and {@code {"end": true}} ends the conversation. Each message becomes the runner record that the child
 * receives: a {@code turn}, an {@code interrupt} or the {@code complete} record. A line that is not one of these
 * messages sends nothing and is answered in {@value #FROM_CHILD_FILE} by
 * {@code {"refused": "<line>", "reason": "<why>"}}.
 * <p>
 * The {@code complete} record follows the end of every turn that was asked for, so an {@code end} that follows a turn
 * lets that turn finish. Until then the conversation still reads the lines after {@code end}, because an
 * {@code interrupt} behind it is the only way to stop a turn that does not finish; it ignores every other line there.
 * <p>
 * Every record of the child is appended as it arrives: its {@code harness_event} records, which are the harness's
 * own events and number in the tens of thousands, to {@value #EVENTS_FILE}, and every other record to
 * {@value #FROM_CHILD_FILE}, so that the asking session reads only the records of its turns. When the child's turn
 * {@code n} ends, a line {@code {"turn_end": n, "status": "<status>"}} follows its last record in
 * {@value #FROM_CHILD_FILE}, after every event of the turn was written to {@value #EVENTS_FILE}. Its status is
 * {@code interrupted} when the turn was interrupted, {@code failed} when it failed and {@code completed} otherwise. The
 * record before the line names why a failed turn failed. A failed turn does not end the conversation while the harness
 * runs: the next turn that the asking session sends continues the same session.
 * The last line, however the child ends, is {@code {"ended": "<status>"}}, where the status is the request's: so an
 * asking session that waits for a {@code turn_end} that never comes sees the end instead.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class Conversation implements TurnSource
{
	/**
	 * The option of a runner launcher that names a conversation's folder.
	 */
	public static final String OPTION = "--conversation";
	/**
	 * The file to which the asking session appends its messages.
	 */
	public static final String TO_CHILD_FILE = "to-child.jsonl";
	/**
	 * The file to which the child's records and the answers to the asking session go, except the harness's events.
	 */
	public static final String FROM_CHILD_FILE = "from-child.jsonl";
	/**
	 * The file to which the harness's events, which the child reports as {@code harness_event} records, go.
	 */
	public static final String EVENTS_FILE = "events.jsonl";
	/**
	 * How many bytes of the asking session's messages are read at a time.
	 */
	private static final int CHUNK_BYTES = 8192;
	/**
	 * The type of the records that carry the harness's own events.
	 */
	private static final String HARNESS_EVENT = "harness_event";
	/**
	 * Why a line is refused when it is not a message.
	 */
	private static final String NOT_A_MESSAGE = "A message is one of {\"turn\": \"<text>\"}, {\"interrupt\": true} " +
		"and {\"end\": true}";
	private final JsonMapper json = JsonMapper.builder().build();
	private final Path toChild;
	private final Path fromChild;
	private final Path events;
	/**
	 * The bytes of the line that the asking session has not finished yet.
	 */
	private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
	/**
	 * The records that were read and not yet taken.
	 */
	private final Queue<String> records = new ArrayDeque<>();
	/**
	 * How many bytes of {@value #TO_CHILD_FILE} were read.
	 */
	private long read;
	/**
	 * Indicates whether the asking session sent {@code end}, which the conversation holds back until every turn that
	 * it asked for has ended.
	 */
	private boolean endRequested;
	/**
	 * Indicates whether the {@code complete} record was taken, so that nothing follows it.
	 */
	private boolean ended;
	/**
	 * How many turns the asking session asked for.
	 */
	private int turnsAsked;
	/**
	 * How many of the child's turns have ended.
	 */
	private int turnsEnded;
	/**
	 * Indicates whether the child reported that its current turn was interrupted.
	 */
	private boolean interrupted;

	/**
	 * Creates a conversation.
	 *
	 * @param folder the conversation's folder
	 */
	private Conversation(Path folder)
	{
		this.toChild = folder.resolve(TO_CHILD_FILE);
		this.fromChild = folder.resolve(FROM_CHILD_FILE);
		this.events = folder.resolve(EVENTS_FILE);
	}

	/**
	 * Opens a conversation in a folder that is new or empty, creating the folder and its files. The folder may
	 * already hold {@value #TO_CHILD_FILE}, because the asking session may append its first message before the
	 * launcher opens the conversation.
	 *
	 * @param folder the conversation's folder
	 * @return the conversation
	 * @throws NullPointerException      if {@code folder} is null
	 * @throws InvalidArgumentsException if {@code folder} is not a folder, or holds another file
	 * @throws IOException               if the folder or its files cannot be created
	 */
	public static Conversation open(Path folder) throws IOException
	{
		requireThat(folder, "folder").isNotNull();
		try
		{
			Files.createDirectories(folder);
		}
		catch (FileAlreadyExistsException _)
		{
			throw new InvalidArgumentsException(OPTION + " names a file instead of a folder: " + folder);
		}
		try (Stream<Path> entries = Files.list(folder))
		{
			if (entries.anyMatch(entry -> !entry.getFileName().toString().equals(TO_CHILD_FILE)))
			{
				throw new InvalidArgumentsException(OPTION + " needs a new or empty folder, and " + folder +
					" holds files");
			}
		}
		Conversation conversation = new Conversation(folder);
		Files.createFile(conversation.fromChild);
		Files.createFile(conversation.events);
		try
		{
			Files.createFile(conversation.toChild);
		}
		catch (FileAlreadyExistsException _)
		{
			// The asking session has already sent a message
		}
		return conversation;
	}

	@Override
	public Optional<String> poll() throws IOException
	{
		synchronized (this)
		{
			if (records.isEmpty() && !ended)
				readMessages();
			String record = records.poll();
			if (record == null && endRequested && !ended && turnsEnded >= turnsAsked)
			{
				ended = true;
				record = json.createObjectNode().put("type", "complete").toString();
			}
			return Optional.ofNullable(record);
		}
	}

	@Override
	public boolean ended()
	{
		synchronized (this)
		{
			return ended && records.isEmpty();
		}
	}

	/**
	 * Reads the messages that the asking session has appended since the last read, a chunk at a time, until one of
	 * them yields a record or no whole line remains.
	 *
	 * @throws IOException if the messages cannot be read, or a refusal cannot be written
	 */
	private void readMessages() throws IOException
	{
		try (FileChannel channel = FileChannel.open(toChild, StandardOpenOption.READ))
		{
			channel.position(read);
			ByteBuffer chunk = ByteBuffer.allocate(CHUNK_BYTES);
			while (records.isEmpty() && !ended)
			{
				chunk.clear();
				int count = channel.read(chunk);
				if (count <= 0)
					return;
				for (int index = 0; index < count && !ended; ++index)
				{
					read += 1;
					byte value = chunk.get(index);
					if (value != '\n')
					{
						partial.write(value);
						continue;
					}
					String line = partial.toString(UTF_8);
					partial.reset();
					message(line);
				}
			}
		}
	}

	/**
	 * Turns one line of the asking session into the record that the child receives, or refuses it.
	 *
	 * @param line the line, without its terminator
	 * @throws IOException if a refusal cannot be written
	 */
	private void message(String line) throws IOException
	{
		if (line.isBlank())
			return;
		JsonNode message;
		try
		{
			message = json.readTree(line);
		}
		catch (JacksonException _)
		{
			if (!endRequested)
				refuse(line);
			return;
		}
		if (endRequested)
		{
			// Only an interrupt of the turn that end waits for still means anything
			if (message.isObject() && message.size() == 1 && isTrue(message.path("interrupt")))
				records.add(json.createObjectNode().put("type", "interrupt").toString());
			return;
		}
		if (!message.isObject() || message.size() != 1)
		{
			refuse(line);
			return;
		}
		JsonNode turn = message.path("turn");
		if (turn.isString())
		{
			records.add(json.createObjectNode().put("type", "turn").put("prompt", turn.asString()).toString());
			++turnsAsked;
			return;
		}
		if (isTrue(message.path("interrupt")))
		{
			records.add(json.createObjectNode().put("type", "interrupt").toString());
			return;
		}
		if (isTrue(message.path("end")))
		{
			endRequested = true;
			return;
		}
		refuse(line);
	}

	/**
	 * Indicates whether a value is {@code true}.
	 *
	 * @param value the value
	 * @return {@code true} if it is the boolean {@code true}
	 */
	private static boolean isTrue(JsonNode value)
	{
		return value.isBoolean() && value.asBoolean();
	}

	/**
	 * Answers a line that is not a message.
	 *
	 * @param line the line
	 * @throws IOException if the answer cannot be written
	 */
	private void refuse(String line) throws IOException
	{
		append(json.createObjectNode().put("refused", line).put("reason", NOT_A_MESSAGE).toString());
	}

	/**
	 * Appends one of the child's records to {@value #EVENTS_FILE} if it is a {@code harness_event}, or else to
	 * {@value #FROM_CHILD_FILE}, followed by a {@code turn_end} line when the record ends a turn. A line that is not
	 * JSON goes to {@value #FROM_CHILD_FILE}.
	 *
	 * @param line the record, without its line terminator
	 * @throws NullPointerException if {@code line} is null
	 * @throws IOException          if the record cannot be written
	 */
	public void childRecord(String line) throws IOException
	{
		requireThat(line, "line").isNotNull();
		synchronized (this)
		{
			String type;
			try
			{
				type = json.readTree(line).path("type").asString("");
			}
			catch (JacksonException _)
			{
				append(fromChild, line);
				return;
			}
			if (type.equals(HARNESS_EVENT))
			{
				append(events, line);
				return;
			}
			append(fromChild, line);
			switch (type)
			{
				case "interrupted" -> interrupted = true;
				case "turn_completed", "turn_failed" ->
				{
					turnsEnded += 1;
					String status;
					if (interrupted)
						status = "interrupted";
					else if (type.equals("turn_failed"))
						status = "failed";
					else
						status = "completed";
					interrupted = false;
					append(json.createObjectNode().put("turn_end", turnsEnded).put("status", status).toString());
				}
				default ->
				{
				}
			}
		}
	}

	/**
	 * Writes the conversation's last line, which says how the request ended.
	 *
	 * @param status the request's status, as its response spells it
	 * @throws NullPointerException     if {@code status} is null
	 * @throws IllegalArgumentException if {@code status} is blank
	 * @throws IOException              if the line cannot be written
	 */
	public void end(String status) throws IOException
	{
		requireThat(status, "status").isNotBlank();
		// A launch that an interrupt of its thread ends still writes its last line, which an interrupt would otherwise
		// stop by closing the file's channel; the interrupt stays pending for the caller
		boolean interrupted = Thread.interrupted();
		try
		{
			synchronized (this)
			{
				append(json.createObjectNode().put("ended", status).toString());
			}
		}
		finally
		{
			if (interrupted)
				Thread.currentThread().interrupt();
		}
	}

	/**
	 * Returns a stream that receives the child's records as bytes, one per line, and writes them both to a transcript
	 * and, as each line ends, to this conversation.
	 *
	 * @param transcript receives the bytes as they are written
	 * @return the stream
	 * @throws NullPointerException if {@code transcript} is null
	 */
	public OutputStream childOutput(OutputStream transcript)
	{
		requireThat(transcript, "transcript").isNotNull();
		return new ChildOutput(transcript);
	}

	/**
	 * Returns the records of the asking session's messages as lines that a reader blocks for: each read waits, a
	 * pause at a time, until a record arrives, and the reader ends after the {@code complete} record that
	 * {@code end} yields.
	 *
	 * @param pause paces the checks for the next message
	 * @return the reader
	 * @throws NullPointerException if {@code pause} is null
	 */
	public Reader records(Pause pause)
	{
		requireThat(pause, "pause").isNotNull();
		return new RecordReader(pause);
	}

	/**
	 * Returns a writer that receives the child's records as text, one per line, and writes them both to a transcript
	 * and, as each line ends, to this conversation.
	 *
	 * @param transcript receives the text as it is written
	 * @return the writer
	 * @throws NullPointerException if {@code transcript} is null
	 */
	public Writer childWriter(Writer transcript)
	{
		requireThat(transcript, "transcript").isNotNull();
		return new ChildWriter(transcript);
	}

	/**
	 * Appends a line to {@value #FROM_CHILD_FILE}.
	 *
	 * @param line the line, without its terminator
	 * @throws IOException if it cannot be written
	 */
	private void append(String line) throws IOException
	{
		append(fromChild, line);
	}

	/**
	 * Appends a line to a file of the conversation.
	 *
	 * @param file the file
	 * @param line the line, without its terminator
	 * @throws IOException if it cannot be written
	 */
	private static void append(Path file, String line) throws IOException
	{
		Files.writeString(file, line + "\n", UTF_8, StandardOpenOption.APPEND);
	}

	@Override
	public void close()
	{
		// Each read and write opens the files for itself
	}

	/**
	 * The records of the asking session's messages, a line each.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	private final class RecordReader extends Reader
	{
		private final Pause pause;
		/**
		 * The line that is being read, with its terminator.
		 */
		private String line = "";
		/**
		 * How many characters of {@link #line} were read.
		 */
		private int position;

		/**
		 * Creates the reader.
		 *
		 * @param pause paces the checks for the next message
		 */
		RecordReader(Pause pause)
		{
			this.pause = pause;
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			if (length == 0)
				return 0;
			while (position == line.length())
			{
				Optional<String> next = poll();
				if (next.isPresent())
				{
					line = next.get() + "\n";
					position = 0;
				}
				else if (ended())
					return -1;
				else
					pause.pause();
			}
			int count = Math.min(length, line.length() - position);
			line.getChars(position, position + count, buffer, offset);
			position += count;
			return count;
		}

		@Override
		public void close()
		{
			// The conversation's files are opened for each read
		}
	}

	/**
	 * The child's records as text, which go to a transcript and, a line at a time, to the conversation.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private final class ChildWriter extends Writer
	{
		private final Writer transcript;
		/**
		 * The text of the record whose line has not ended yet; guarded by this writer.
		 */
		private final StringBuilder line = new StringBuilder();

		/**
		 * Creates the writer.
		 *
		 * @param transcript receives the text as it is written
		 */
		ChildWriter(Writer transcript)
		{
			this.transcript = transcript;
		}

		@Override
		public void write(char[] buffer, int offset, int length) throws IOException
		{
			synchronized (this)
			{
				transcript.write(buffer, offset, length);
				for (int index = offset; index < offset + length; ++index)
				{
					char value = buffer[index];
					if (value != '\n')
					{
						line.append(value);
						continue;
					}
					String record = line.toString();
					line.setLength(0);
					childRecord(record);
				}
			}
		}

		@Override
		public void flush() throws IOException
		{
			transcript.flush();
		}

		@Override
		public void close() throws IOException
		{
			transcript.close();
		}
	}

	/**
	 * The child's records as bytes, which go to a transcript and, a line at a time, to the conversation.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	private final class ChildOutput extends OutputStream
	{
		private final OutputStream transcript;
		/**
		 * The bytes of the record whose line has not ended yet.
		 */
		private final ByteArrayOutputStream line = new ByteArrayOutputStream();

		/**
		 * Creates the stream.
		 *
		 * @param transcript receives the bytes as they are written
		 */
		ChildOutput(OutputStream transcript)
		{
			this.transcript = transcript;
		}

		@Override
		public void write(int value) throws IOException
		{
			transcript.write(value);
			split(value);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException
		{
			transcript.write(bytes, offset, length);
			for (int index = offset; index < offset + length; ++index)
				split(bytes[index]);
		}

		/**
		 * Adds one byte to the current record, and writes the record to the conversation when its line ends.
		 *
		 * @param value the byte
		 * @throws IOException if the record cannot be written
		 */
		private void split(int value) throws IOException
		{
			if (value != '\n')
			{
				line.write(value);
				return;
			}
			String record = line.toString(UTF_8);
			line.reset();
			childRecord(record);
		}

		@Override
		public void flush() throws IOException
		{
			transcript.flush();
		}
	}
}
