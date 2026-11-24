package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The project, transcripts and completed turn records exported by the broker from a claimed retained trial.
 * The runner replaces one private snapshot before it records a new ready checkpoint. A published prefix contains
 * that snapshot's turn evidence and one event log and completion reply per completed turn.
 */
public final class RetainedTrialPrefix
{
	private static final Path CHECKPOINT = Path.of("turn-checkpoint.json");
	private static final Path SNAPSHOT_DIRECTORY = Path.of("retained-turn");
	private static final String SNAPSHOT_STAGING_PREFIX = ".retained-turn-";
	private static final Path RESPONSE_DIRECTORY = Path.of("retained-prefix");
	private static final Path SNAPSHOT_STAMP = Path.of("snapshot.json");
	private static final Path ARTIFACTS = Path.of("artifacts");
	private static final Path TRANSCRIPTS = ARTIFACTS.resolve(NestedSession.SESSION_TRANSCRIPTS);
	private static final String VERSION_FIELD = "format_version";
	private static final int FORMAT_VERSION = 1;
	private static final String TURN_FIELD = "turn_number";
	private static final String NATIVE_ROOT_FIELD = "native_root";
	private static final String TURN_FILE_PREFIX = "turn-";
	private static final String EVENTS_SUFFIX = ".events";
	private static final String REPLY_SUFFIX = ".reply.json";
	private static final int REPLY_FIELDS = 5;
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final ObjectReader METADATA = JSON.readerFor(JsonNode.class).with(
		DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

	/**
	 * Captures a private turn snapshot before the runner marks that turn ready. The runner uses the returned snapshot
	 * as the source of the live observer's copy. If it records a native root transcript basename, its copied file
	 * must have at least one complete line.
	 *
	 * @param session            the private nested session
	 * @param turn               the one-based turn that the harness just completed
	 * @param expectedNativeRoot the required native root transcript basename, or empty when none is required
	 * @return the private snapshot directory
	 * @throws NullPointerException     if either reference argument is null
	 * @throws IllegalArgumentException if the turn is not positive or the transcript name is not a basename
	 * @throws IOException              if any required evidence is absent or cannot be copied
	 */
	public static Path capture(Path session, int turn, Optional<String> expectedNativeRoot) throws IOException
	{
		return capture(session, turn, expectedNativeRoot, Optional.empty());
	}

	/**
	 * Captures the turn and any draft-link declarations selected by the original runner.
	 *
	 * @param session the private nested session
	 * @param turn the one-based turn that the harness just completed
	 * @param expectedNativeRoot the required native root transcript basename, or empty when none is required
	 * @param linkDirectory the selected project directory, or empty when no link declarations are captured
	 * @return the private snapshot directory
	 * @throws IOException if any required evidence is absent or cannot be copied
	 */
	static Path capture(Path session, int turn, Optional<String> expectedNativeRoot, Optional<Path> linkDirectory)
		throws IOException
	{
		requireThat(session, "session").isNotNull();
		requireThat(turn, "turn").isPositive();
		requireThat(expectedNativeRoot, "expectedNativeRoot").isNotNull();
		if (expectedNativeRoot.isPresent() && !isBasename(expectedNativeRoot.get()))
			throw new IllegalArgumentException("The retained trial native transcript requires a basename");
		if (!hasProjects(session))
			throw new IOException("The retained trial prefix requires its private project and initial-project copies");

		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path pending = cleanup.add(Files.createTempDirectory(session, SNAPSHOT_STAGING_PREFIX));
			copyTurn(session, pending, expectedNativeRoot);
			if (linkDirectory.isPresent())
				ResultProjectLinks.capture(session, pending, linkDirectory.get());
			ObjectNode stamp = JSON.createObjectNode().put(VERSION_FIELD, FORMAT_VERSION).put(TURN_FIELD, turn);
			expectedNativeRoot.ifPresent(root -> stamp.put(NATIVE_ROOT_FIELD, root));
			Files.write(pending.resolve(SNAPSHOT_STAMP), JSON.writeValueAsBytes(stamp));

			Path snapshot = session.resolve(SNAPSHOT_DIRECTORY);
			TemporaryDirectories.deleteRecursively(snapshot);
			Files.move(pending, snapshot, StandardCopyOption.ATOMIC_MOVE);
			return snapshot;
		}
	}

	/**
	 * Identifies snapshot paths reserved for the private recovery copy in a nested session.
	 *
	 * @param relative the path relative to the nested session
	 * @return whether the path names a snapshot or its staging directory
	 */
	static boolean isPrivateSnapshot(Path relative)
	{
		return relative.getNameCount() == 1 && (relative.equals(SNAPSHOT_DIRECTORY) ||
			relative.toString().startsWith(SNAPSHOT_STAGING_PREFIX));
	}

	/**
	 * Reads the runner's checkpoint before another turn has started.
	 *
	 * @param session the private session directory or a published prefix
	 * @return the completed turn count, or empty if the checkpoint is absent, malformed or in progress
	 * @throws NullPointerException if {@code session} is null
	 * @throws IOException          if checkpoint I/O fails
	 */
	public static OptionalInt readReadyTurns(Path session) throws IOException
	{
		requireThat(session, "session").isNotNull();
		if (!Files.isDirectory(session, NOFOLLOW_LINKS))
			return OptionalInt.empty();
		Optional<JsonNode> metadata = readMetadata(session.resolve(CHECKPOINT));
		if (metadata.isEmpty())
			return OptionalInt.empty();
		JsonNode checkpoint = metadata.get();
		if (checkpoint.size() != 3 || !checkpoint.path("state").asString("").equals("ready"))
			return OptionalInt.empty();
		return OptionalInt.of(checkpoint.path(TURN_FIELD).intValue());
	}

	/**
	 * Publishes the evidence while the broker holds the exact retained slot claim and before reopening the runner.
	 * A reader sees the prefix directory only after all its current files have been copied.
	 *
	 * @param session        the private nested session held by the broker
	 * @param response       the request's response directory
	 * @param completedTurns the number of completed turns that the broker claimed
	 * @return the response's retained evidence directory
	 * @throws NullPointerException     if either reference argument is null
	 * @throws IllegalArgumentException if the turn count is negative
	 * @throws IOException              if the source is incomplete or the prefix cannot be published
	 */
	public static Path publish(Path session, Path response, int completedTurns) throws IOException
	{
		requireThat(session, "session").isNotNull();
		requireThat(response, "response").isNotNull();
		requireThat(completedTurns, "completedTurns").isGreaterThanOrEqualTo(0);

		if (!validates(session, completedTurns) || !Files.isDirectory(response, NOFOLLOW_LINKS))
			throw new IOException("The retained trial prefix does not match the claimed completed turns");
		Path published = response.resolve(RESPONSE_DIRECTORY);
		if (Files.exists(published, NOFOLLOW_LINKS))
			throw new IOException("The retained trial prefix is already published to this response");

		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path pending = cleanup.add(Files.createTempDirectory(response, ".retained-prefix-"));
			copyRegular(session.resolve(CHECKPOINT), pending.resolve(CHECKPOINT));
			if (completedTurns > 0)
			{
				Path snapshot = session.resolve(SNAPSHOT_DIRECTORY);
				Optional<JsonNode> stamp = readMetadata(snapshot.resolve(SNAPSHOT_STAMP));
				if (stamp.isEmpty())
					throw new IOException("The retained trial prefix lost its snapshot stamp");
				copyTurn(snapshot, pending, nativeRoot(stamp.get()));
				Path source = session.resolve(ARTIFACTS);
				Path target = Files.createDirectories(pending.resolve(ARTIFACTS));
				for (int index = 0; index < completedTurns; ++index)
				{
					int turn = index + 1;
					copyRegular(turnFile(source, turn, EVENTS_SUFFIX), turnFile(target, turn, EVENTS_SUFFIX));
					copyRegular(turnFile(source, turn, REPLY_SUFFIX), turnFile(target, turn, REPLY_SUFFIX));
				}
			}
			return Files.move(pending, published, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/**
	 * Opens the retained evidence of a broker response at the claimed turn count.
	 *
	 * @param response       the request's response directory
	 * @param completedTurns the number of completed turns that the broker claimed
	 * @return the retained evidence
	 * @throws NullPointerException     if {@code response} is null
	 * @throws IllegalArgumentException if the turn count is negative
	 * @throws IOException              if the retained evidence is absent, incomplete or cannot be read
	 */
	public static RetainedTrialPrefix open(Path response, int completedTurns) throws IOException
	{
		requireThat(response, "response").isNotNull();
		requireThat(completedTurns, "completedTurns").isGreaterThanOrEqualTo(0);
		Path directory = response.resolve(RESPONSE_DIRECTORY);
		if (!Files.isDirectory(response, NOFOLLOW_LINKS) || readReadyTurns(directory).orElse(-1) != completedTurns ||
			(completedTurns > 0 && (!hasProjects(directory) || !hasTurns(directory, completedTurns))))
		{
			throw new IOException("The retained trial prefix cannot supply the claimed completed turns");
		}
		return new RetainedTrialPrefix(directory, completedTurns);
	}

	private final Path directory;
	private final int completedTurns;

	/**
	 * Keeps the location and count of validated broker evidence.
	 *
	 * @param directory      the exported evidence
	 * @param completedTurns the number of completed turns
	 */
	private RetainedTrialPrefix(Path directory, int completedTurns)
	{
		this.directory = directory;
		this.completedTurns = completedTurns;
	}

	/**
	 * Returns the broker's exported folder containing the latest completed turn's project and native evidence.
	 *
	 * @return the evidence directory
	 */
	public Path directory()
	{
		return directory;
	}

	/**
	 * Returns how many completed turns the broker exported.
	 *
	 * @return the completed turn count
	 */
	public int completedTurns()
	{
		return completedTurns;
	}

	/**
	 * Opens one completed turn's UTF-8 harness events in their original order. The caller reads one turn at a time
	 * in ascending turn number and closes each reader after replay.
	 *
	 * @param turn the one-based turn
	 * @return the event reader, which the caller must close
	 * @throws IllegalArgumentException if the turn is outside the completed prefix
	 * @throws IOException              if the evidence cannot be read
	 */
	public BufferedReader openEvents(int turn) throws IOException
	{
		return new BufferedReader(new InputStreamReader(openTurn(turn, EVENTS_SUFFIX), UTF_8.newDecoder()));
	}

	/**
	 * Opens one completed turn's unchanged reply bytes.
	 *
	 * @param turn the one-based turn
	 * @return the reply stream, which the caller must close
	 * @throws IllegalArgumentException if the turn is outside the completed prefix
	 * @throws IOException              if the reply cannot be read
	 */
	public InputStream openReply(int turn) throws IOException
	{
		return openTurn(turn, REPLY_SUFFIX);
	}

	/**
	 * Checks the ready checkpoint, latest snapshot and every exact turn file while the broker holds the slot.
	 *
	 * @param session       the private nested session
	 * @param expectedTurns the expected completed turn count
	 * @return whether the session has the complete prefix for that count
	 * @throws IOException if evidence I/O fails
	 */
	static boolean validates(Path session, int expectedTurns) throws IOException
	{
		if (expectedTurns < 0 || readReadyTurns(session).orElse(-1) != expectedTurns)
			return false;
		if (expectedTurns == 0)
			return !Files.exists(session.resolve(SNAPSHOT_DIRECTORY), NOFOLLOW_LINKS);

		Path snapshot = session.resolve(SNAPSHOT_DIRECTORY);
		if (!hasProjects(snapshot))
			return false;
		Optional<JsonNode> metadata = readMetadata(snapshot.resolve(SNAPSHOT_STAMP));
		if (metadata.isEmpty())
			return false;
		JsonNode stamp = metadata.get();
		if (stamp.path(TURN_FIELD).intValue() != expectedTurns)
			return false;
		Optional<String> expectedNativeRoot = nativeRoot(stamp);
		int fields = 2;
		if (expectedNativeRoot.isPresent())
			fields = 3;
		return stamp.size() == fields && hasNativeRoot(snapshot, expectedNativeRoot) && hasTurns(session, expectedTurns);
	}

	/**
	 * Reads the shared version and completed-turn fields from a small checkpoint or snapshot stamp.
	 *
	 * @param file the metadata file
	 * @return the document or empty for missing or malformed metadata
	 * @throws IOException if metadata I/O fails
	 */
	private static Optional<JsonNode> readMetadata(Path file) throws IOException
	{
		if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
			return Optional.empty();
		byte[] bytes = BoundedInput.readBytes(Files.newInputStream(file, NOFOLLOW_LINKS), file.getFileName(),
			RequestArchive.MAXIMUM_MANIFEST_BYTES, "The retained trial checkpoint");
		try
		{
			JsonNode value = METADATA.readValue(bytes);
			JsonNode version = value.path(VERSION_FIELD);
			JsonNode turn = value.path(TURN_FIELD);
			if (value.isObject() && version.isInt() && version.intValue() == FORMAT_VERSION && turn.isInt() &&
				turn.intValue() >= 0)
			{
				return Optional.of(value);
			}
			return Optional.empty();
		}
		catch (JacksonException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Reads the expected native root transcript from a snapshot stamp.
	 *
	 * @param stamp the snapshot's metadata
	 * @return the root basename, or empty when absent or malformed
	 */
	private static Optional<String> nativeRoot(JsonNode stamp)
	{
		JsonNode root = stamp.path(NATIVE_ROOT_FIELD);
		if (root.isString() && isBasename(root.asString()))
			return Optional.of(root.asString());
		return Optional.empty();
	}

	/**
	 * Checks that a native transcript name can select only a file immediately within the transcript directory.
	 *
	 * @param name the supplied transcript name
	 * @return whether the name is a portable basename
	 */
	private static boolean isBasename(String name)
	{
		try
		{
			Path path = Path.of(name);
			return !name.isBlank() && path.getRoot() == null && path.getNameCount() == 1 && path.toString().equals(name) &&
				!name.equals(".") && !name.equals("..") && name.indexOf('\\') < 0 && name.indexOf(':') < 0;
		}
		catch (InvalidPathException _)
		{
			return false;
		}
	}

	/**
	 * Copies and verifies the project, initial project and session transcripts under their existing turn-copy names.
	 *
	 * @param source             the private nested session or its latest turn snapshot
	 * @param destination        the empty private copy
	 * @param expectedNativeRoot the native root transcript basename, when required
	 * @throws IOException if the copy omitted any required evidence
	 */
	private static void copyTurn(Path source, Path destination, Optional<String> expectedNativeRoot) throws IOException
	{
		if (!ResultsCopy.copyTurn(source, destination).isEmpty() || !hasProjects(destination))
			throw new IOException("The retained trial prefix could not copy the project and initial project");
		if (!hasNativeRoot(destination, expectedNativeRoot))
			throw new IOException("The retained trial prefix requires the completed root native transcript");
	}

	/**
	 * Checks that neither project directory nor its root is missing or a link.
	 *
	 * @param root the session, captured turn or exported prefix
	 * @return whether both project copies are present
	 */
	private static boolean hasProjects(Path root)
	{
		return Files.isDirectory(root, NOFOLLOW_LINKS) && Files.isDirectory(root.resolve("project"), NOFOLLOW_LINKS) &&
			Files.isDirectory(root.resolve("initial-project"), NOFOLLOW_LINKS);
	}

	/**
	 * Checks the named native file for a nonempty prefix ending in a completed line. The harness remains responsible
	 * for choosing the name and producing that prefix.
	 *
	 * @param root               the captured or exported turn
	 * @param expectedNativeRoot the required basename, or empty when native evidence is optional
	 * @return whether the required native transcript is present
	 * @throws IOException if transcript I/O fails
	 */
	private static boolean hasNativeRoot(Path root, Optional<String> expectedNativeRoot) throws IOException
	{
		if (expectedNativeRoot.isEmpty())
			return true;
		Path transcripts = root.resolve(TRANSCRIPTS);
		Path file = transcripts.resolve(expectedNativeRoot.get());
		if (!Files.isDirectory(root.resolve(ARTIFACTS), NOFOLLOW_LINKS) ||
			!Files.isDirectory(transcripts, NOFOLLOW_LINKS) || !Files.isRegularFile(file, NOFOLLOW_LINKS))
		{
			return false;
		}

		try (SeekableByteChannel bytes = Files.newByteChannel(file, StandardOpenOption.READ, NOFOLLOW_LINKS))
		{
			long size = bytes.size();
			if (size == 0)
				return false;
			ByteBuffer last = ByteBuffer.allocate(1);
			bytes.position(size - 1);
			return bytes.read(last) == 1 && last.get(0) == '\n';
		}
	}

	/**
	 * Checks each required turn's event log and completion reply without buffering the event logs or whole prefix.
	 *
	 * @param root           the source session or exported prefix
	 * @param completedTurns the positive expected completed turn count
	 * @return whether every expected event log and terminal reply is present
	 * @throws IOException if evidence I/O fails
	 */
	private static boolean hasTurns(Path root, int completedTurns) throws IOException
	{
		Path artifacts = root.resolve(ARTIFACTS);
		if (!Files.isDirectory(artifacts, NOFOLLOW_LINKS))
			return false;
		for (int index = 0; index < completedTurns; ++index)
		{
			int turn = index + 1;
			Path events = turnFile(artifacts, turn, EVENTS_SUFFIX);
			Path reply = turnFile(artifacts, turn, REPLY_SUFFIX);
			if (!Files.isRegularFile(events, NOFOLLOW_LINKS) || !Files.isRegularFile(reply, NOFOLLOW_LINKS) ||
				!isCompletedReply(reply))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks the bridge's exact successful completion reply shape while retaining the original file bytes.
	 *
	 * @param reply the regular reply file
	 * @return whether the file supplies just one complete bridge reply
	 * @throws IOException if reply I/O fails
	 */
	private static boolean isCompletedReply(Path reply) throws IOException
	{
		Set<String> fields = new HashSet<>();
		try (InputStream input = Files.newInputStream(reply, NOFOLLOW_LINKS);
			JsonParser parser = JSON.createParser(input))
		{
			if (parser.nextToken() != JsonToken.START_OBJECT)
				return false;
			while (parser.nextToken() == JsonToken.PROPERTY_NAME)
			{
				String field = parser.currentName();
				parser.nextToken();
				if (!fields.add(field) || !isReplyField(field, parser))
					return false;
			}
			return fields.size() == REPLY_FIELDS && parser.currentToken() == JsonToken.END_OBJECT &&
				parser.nextToken() == null;
		}
		catch (JacksonIOException e)
		{
			throw new IOException("The retained trial prefix could not read " + reply.getFileName(), e);
		}
		catch (JacksonException _)
		{
			return false;
		}
	}

	/**
	 * Checks the shape of one supported reply field.
	 *
	 * @param field  the field name
	 * @param parser positioned on the field's value
	 * @return whether the field has its required value shape
	 */
	private static boolean isReplyField(String field, JsonParser parser)
	{
		JsonToken token = parser.currentToken();
		return switch (field)
		{
			case "type" -> token == JsonToken.VALUE_STRING && parser.getString().equals("turn_completed");
			case "error", "assistant_response" -> token == JsonToken.VALUE_STRING;
			case "exit_code" ->
			{
				if (token != JsonToken.VALUE_NUMBER_INT)
					yield false;
				parser.getIntValue();
				yield true;
			}
			case "assistant_messages" ->
			{
				if (token != JsonToken.START_ARRAY)
					yield false;
				JsonToken entry;
				do
					entry = parser.nextToken();
				while (entry == JsonToken.VALUE_STRING);
				yield entry == JsonToken.END_ARRAY;
			}
			default -> false;
		};
	}

	/**
	 * Resolves an exact numbered file below the trusted artifacts folder.
	 *
	 * @param artifacts the trusted artifacts folder
	 * @param turn      the one-based turn number
	 * @param suffix    the event or reply file suffix
	 * @return the numbered artifact path
	 */
	private static Path turnFile(Path artifacts, int turn, String suffix)
	{
		return artifacts.resolve(TURN_FILE_PREFIX + turn + suffix);
	}

	/**
	 * Opens an artifact from the published prefix without following a link.
	 *
	 * @param turn   the one-based turn number
	 * @param suffix the event or reply file suffix
	 * @return the artifact stream, which the caller must close
	 * @throws IllegalArgumentException if the turn is outside the prefix
	 * @throws IOException              if the artifact is missing or cannot be read
	 */
	private InputStream openTurn(int turn, String suffix) throws IOException
	{
		requireThat(turn, "turn").isPositive().isLessThanOrEqualTo(completedTurns, "completedTurns");
		Path artifacts = directory.resolve(ARTIFACTS);
		Path file = turnFile(artifacts, turn, suffix);
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !Files.isDirectory(artifacts, NOFOLLOW_LINKS) ||
			!Files.isRegularFile(file, NOFOLLOW_LINKS))
		{
			throw new IOException("The retained trial prefix is missing " + file.getFileName());
		}
		return Files.newInputStream(file, NOFOLLOW_LINKS);
	}

	/**
	 * Copies one checkpoint or artifact's exact bytes without following a link.
	 *
	 * @param source      the trusted regular file
	 * @param destination the new evidence file
	 * @throws IOException if the file is missing or cannot be copied
	 */
	private static void copyRegular(Path source, Path destination) throws IOException
	{
		if (!Files.isRegularFile(source, NOFOLLOW_LINKS))
			throw new IOException("The retained trial prefix is missing " + source.getFileName());
		try (InputStream bytes = Files.newInputStream(source, NOFOLLOW_LINKS))
		{
			Files.copy(bytes, destination);
		}
	}
}
