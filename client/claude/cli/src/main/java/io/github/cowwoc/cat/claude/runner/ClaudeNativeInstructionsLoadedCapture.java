package io.github.cowwoc.cat.claude.runner;

import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import io.github.cowwoc.cat.common.BoundedInput;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Retains raw Claude InstructionsLoaded hook input before interpreting its contents.
 */
public final class ClaudeNativeInstructionsLoadedCapture
{
	private static final String RESOURCE = "/io/github/cowwoc/cat/claude/runner/native-instructions-loaded-capture.sh";
	private static final int MAXIMUM_EVENT_BYTES = 8_388_608;
	private static final int MAXIMUM_INDEX_BYTES = 1_048_576;

	/**
	 * Installs a command and private inbox beneath the owned run home before Claude starts.
	 *
	 * @param run the owned run
	 * @return the absolute capture command path
	 * @throws IOException if the command cannot be installed
	 */
	public static Path install(HarnessRun run) throws IOException
	{
		Path inbox = inbox(run);
		Files.createDirectories(inbox);
		Files.setPosixFilePermissions(inbox, Set.of(PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		Path command = inbox.resolve("capture.sh");
		try (InputStream resource = ClaudeNativeInstructionsLoadedCapture.class.getResourceAsStream(RESOURCE))
		{
			if (resource == null)
				throw new IOException("Missing native InstructionsLoaded capture command resource");
			Files.copy(resource, command);
		}
		Files.setPosixFilePermissions(command, Set.of(PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		return command.toAbsolutePath().normalize();
	}

	/**
	 * Copies each completed event, then seals a raw-evidence index after the native process closes.
	 *
	 * @param run the owned run
	 * @throws IOException if the inbox or retained evidence cannot be trusted or written
	 */
	public static void retain(HarnessRun run) throws IOException
	{
		Path sealed = run.directory().resolve("artifacts/native-instructions-loaded/index.json");
		if (Files.exists(sealed, LinkOption.NOFOLLOW_LINKS))
		{
			validateSealed(run, sealed);
			return;
		}
		Path inbox = inbox(run);
		if (!Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Native InstructionsLoaded inbox is missing: " + inbox);
		List<Path> complete = new ArrayList<>();
		int pending = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(inbox))
		{
			for (Path entry : entries)
			{
				if (Files.isSymbolicLink(entry))
					throw new IOException("Native InstructionsLoaded inbox contains a symlink: " + entry);
				String name = entry.getFileName().toString();
				if (name.equals("capture.sh"))
					continue;
				if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Native InstructionsLoaded inbox contains a non-file: " + entry);
				if (name.matches("\\.pending\\.[A-Za-z0-9]{8}\\.json"))
					complete.add(entry);
				else if (name.matches("\\.pending\\.[A-Za-z0-9]{8}"))
					pending += 1;
				else
					throw new IOException("Unexpected native InstructionsLoaded inbox file: " + entry);
			}
		}
		complete.sort(Comparator.comparing(path -> path.getFileName().toString()));
		Path retained = run.directory().resolve("artifacts/native-instructions-loaded");
		Files.createDirectories(retained);
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			ObjectNode index = scope.json().createObjectNode();
			index.put("schemaVersion", 1);
			index.put("runId", run.id());
			index.put("producer", "claude-settings-InstructionsLoaded");
			index.put("enabled", true);
			if (pending == 0 && !complete.isEmpty())
				index.put("state", "SEALED");
			else
				index.put("state", "LIMITED");
			if (pending > 0)
				index.put("limitation", "One or more hook writers had not completed at finalization");
			else if (complete.isEmpty())
				index.put("limitation", "No native InstructionsLoaded path signal was received");
			index.put("pendingFileCount", pending);
			ArrayNode events = index.putArray("events");
			int ordinal = 0;
			for (Path source : complete)
			{
				ordinal += 1;
				String filename = "%06d.json".formatted(ordinal);
				Path destination = retained.resolve(filename);
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
					throw new IOException("Native InstructionsLoaded evidence already exists: " + destination);
				Files.copy(source, destination, LinkOption.NOFOLLOW_LINKS);
				Files.setPosixFilePermissions(destination, Set.of(PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE));
				byte[] bytes = BoundedInput.readBytes(Files.newInputStream(destination), destination,
					MAXIMUM_EVENT_BYTES, "Native InstructionsLoaded event");
				events.addObject().put("ordinal", ordinal).put("file", filename).put("sha256", sha256(bytes)).
					put("byteCount", bytes.length);
			}
			Path temporaryIndex = Files.createTempFile(retained, ".index-", ".tmp");
			Files.writeString(temporaryIndex, scope.json().writeValueAsString(index));
			Files.move(temporaryIndex, retained.resolve("index.json"), StandardCopyOption.ATOMIC_MOVE);
		}
	}

	/**
	 * Validates a previously sealed index and all of its claimed raw events on restart.
	 *
	 * @param run the owned run
	 * @param sealed the claimed index
	 * @throws IOException if the claim or any event is corrupt
	 */
	private static void validateSealed(HarnessRun run, Path sealed) throws IOException
	{
		JsonNode index = readIndex(run, sealed);
		for (JsonNode entry : index.path("events"))
			readRaw(run, entry.path("ordinal").asInt());
	}

	/**
	 * Reads a claimed raw event after validating its sealed index and hash.
	 *
	 * @param run the owned run
	 * @param ordinal the one-based retained event ordinal
	 * @return the exact raw event bytes
	 * @throws IOException if the index or event is missing or corrupt
	 */
	public static byte[] readRaw(HarnessRun run, int ordinal) throws IOException
	{
		Path retained = run.directory().resolve("artifacts/native-instructions-loaded");
		Path indexFile = retained.resolve("index.json");
		JsonNode index = readIndex(run, indexFile);
		Optional<byte[]> selected = Optional.empty();
		int expectedOrdinal = 0;
		for (JsonNode entry : index.path("events"))
		{
			expectedOrdinal += 1;
			if (!(entry instanceof ObjectNode object) ||
				!Set.copyOf(object.propertyNames()).equals(Set.of("ordinal", "file", "sha256", "byteCount")) ||
				!entry.path("ordinal").isIntegralNumber() || !entry.path("byteCount").isIntegralNumber() ||
				!entry.path("file").isString() || !entry.path("sha256").isString() ||
				entry.path("ordinal").asInt() != expectedOrdinal)
				throw new IOException("Native InstructionsLoaded index entry is invalid");
			String filename = entry.path("file").asString();
			if (!filename.equals("%06d.json".formatted(expectedOrdinal)))
				throw new IOException("Native InstructionsLoaded index file is invalid");
			Path path = retained.resolve(filename);
			if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				throw new IOException("Native InstructionsLoaded event is missing: " + path);
			byte[] bytes = BoundedInput.readBytes(Files.newInputStream(path), path, MAXIMUM_EVENT_BYTES,
				"Native InstructionsLoaded event");
			if (bytes.length != entry.path("byteCount").asLong() ||
				!sha256(bytes).equals(entry.path("sha256").asString()))
				throw new IOException("Native InstructionsLoaded event hash mismatch: " + path);
			if (expectedOrdinal == ordinal)
				selected = Optional.of(bytes);
		}
		if (selected.isPresent())
			return selected.orElseThrow();
		throw new IOException("Native InstructionsLoaded ordinal is absent: " + ordinal);
	}

	/**
	 * Reads the exact sealed index contract and validates its run identity.
	 *
	 * @param run the owned run
	 * @param indexFile the claimed index path
	 * @return the validated index object
	 * @throws IOException if its schema or identity is invalid
	 */
	private static JsonNode readIndex(HarnessRun run, Path indexFile) throws IOException
	{
		if (!Files.isRegularFile(indexFile, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Native InstructionsLoaded index is missing");
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			JsonNode index = scope.json().readTree(BoundedInput.readUtf8(indexFile, MAXIMUM_INDEX_BYTES,
				"Native InstructionsLoaded index"));
			if (!(index instanceof ObjectNode object))
				throw new IOException("Native InstructionsLoaded index shape is invalid");
			boolean limited = "LIMITED".equals(index.path("state").asString());
			Set<String> fields = Set.of("schemaVersion", "runId", "producer", "enabled", "state",
				"pendingFileCount", "events");
			if (limited)
				fields = Set.of("schemaVersion", "runId", "producer", "enabled", "state",
					"pendingFileCount", "events", "limitation");
			if (!Set.copyOf(object.propertyNames()).equals(fields) ||
				!index.path("schemaVersion").isIntegralNumber() || index.path("schemaVersion").asInt() != 1 ||
				!run.id().equals(index.path("runId").asString()) ||
				!"claude-settings-InstructionsLoaded".equals(index.path("producer").asString()) ||
				!index.path("enabled").isBoolean() || !index.path("enabled").asBoolean() ||
				!(limited || "SEALED".equals(index.path("state").asString())) ||
				!index.path("pendingFileCount").isIntegralNumber() ||
				index.path("pendingFileCount").asInt() < 0 || !index.path("events").isArray() ||
				(limited && index.path("limitation").asString().isBlank()))
				throw new IOException("Native InstructionsLoaded index identity or shape is invalid");
			return index;
		}
	}

	/**
	 * Resolves the invocation-owned hook inbox.
	 *
	 * @param run the owned run
	 * @return the inbox path
	 */
	private static Path inbox(HarnessRun run)
	{
		return run.directory().resolve("home/.cat/native-prompt-path");
	}

	/**
	 * Hashes one retained raw event.
	 *
	 * @param bytes event bytes
	 * @return lowercase SHA-256 hex
	 */
	static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ClaudeNativeInstructionsLoadedCapture()
	{
	}
}
