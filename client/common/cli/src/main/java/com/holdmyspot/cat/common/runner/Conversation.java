package com.holdmyspot.cat.common.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * A conversation between a session and a session that it requested, held through two files of a folder that the
 * asking session's commands may write, because appended files work in every harness's sandbox and need no reader to
 * be present when a message is written.
 * <p>
 * The asking session appends its messages to {@value #TO_CHILD_FILE}, one JSON object per line:
 * {@code {"turn": "<text>"}} asks for the child's next turn, {@code {"interrupt": true}} interrupts the turn in
 * progress, and {@code {"end": true}} ends the conversation. Each message becomes the runner record that the child
 * receives: a {@code turn}, an {@code interrupt} or the {@code complete} record. A line that is not one of these
 * messages sends nothing and is answered in {@value #FROM_CHILD_FILE} by
 * {@code {"refused": "<line>", "reason": "<why>"}}. Nothing after {@code end} is read.
 * <p>
 * Every record of the child is appended to {@value #FROM_CHILD_FILE} as it arrives. When the child's turn {@code n}
 * ends, a line {@code {"turn_end": n, "status": "<status>"}} follows its last record, whose status is
 * {@code interrupted} when the turn was interrupted, {@code failed} when it failed and {@code completed} otherwise.
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
	 * The file to which the child's records and the answers to the asking session go.
	 */
	public static final String FROM_CHILD_FILE = "from-child.jsonl";
	/**
	 * How many bytes of the asking session's messages are read at a time.
	 */
	private static final int CHUNK_BYTES = 8192;
	/**
	 * Why a line is refused when it is not a message.
	 */
	private static final String NOT_A_MESSAGE = "A message is one of {\"turn\": \"<text>\"}, {\"interrupt\": true} " +
		"and {\"end\": true}";
	private final JsonMapper json = JsonMapper.builder().build();
	private final Path toChild;
	private final Path fromChild;
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
	 * Indicates whether the asking session ended the conversation.
	 */
	private boolean ended;
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
	}

	/**
	 * Opens a conversation in a folder that is new or empty, creating the folder and both files. The folder may
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
			return Optional.ofNullable(records.poll());
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
			refuse(line);
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
			return;
		}
		if (isTrue(message.path("interrupt")))
		{
			records.add(json.createObjectNode().put("type", "interrupt").toString());
			return;
		}
		if (isTrue(message.path("end")))
		{
			records.add(json.createObjectNode().put("type", "complete").toString());
			ended = true;
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
	 * Appends one of the child's records to {@value #FROM_CHILD_FILE}, followed by a {@code turn_end} line when the
	 * record ends a turn.
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
			append(line);
			String type;
			try
			{
				type = json.readTree(line).path("type").asString("");
			}
			catch (JacksonException _)
			{
				return;
			}
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
	 * Appends a line to {@value #FROM_CHILD_FILE}.
	 *
	 * @param line the line, without its terminator
	 * @throws IOException if it cannot be written
	 */
	private void append(String line) throws IOException
	{
		Files.writeString(fromChild, line + "\n", UTF_8, StandardOpenOption.APPEND);
	}

	@Override
	public void close()
	{
		// Each read and write opens the files for itself
	}
}
