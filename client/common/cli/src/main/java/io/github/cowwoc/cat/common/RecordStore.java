package io.github.cowwoc.cat.common;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads, atomically writes, and locks persisted JSON records.
 */
public final class RecordStore
{
	private static final int RECORD_APPEARANCE_ATTEMPTS = 100;
	private static final long RECORD_APPEARANCE_WAIT_MILLIS = 10;
	private static final Object[] LOCKS = createLocks();

	/**
	 * Prevents construction of this static utility.
	 */
	private RecordStore()
	{
	}

	/**
	 * Reads a JSON record, tolerating only the bounded interval during an atomic replacement.
	 *
	 * @param json the JSON codec
	 * @param path the record path
	 * @return the parsed record
	 * @throws IOException if the record remains absent or invalid
	 */
	public static JsonNode read(JsonMapper json, Path path) throws IOException
	{
		for (int attempt = 1; attempt <= RECORD_APPEARANCE_ATTEMPTS; ++attempt)
		{
			if (Files.isRegularFile(path))
				try
				{
					return json.readTree(path.toFile());
				}
				catch (JacksonException e)
				{
					throw new IOException("Invalid JSON document: " + path, e);
				}
			if (attempt < RECORD_APPEARANCE_ATTEMPTS)
				waitForAppearance(path);
		}
		throw new IOException("Expected regular file: " + path);
	}

	/**
	 * Atomically writes a JSON record.
	 *
	 * @param json the JSON codec
	 * @param destination the record path
	 * @param contents the record contents
	 * @throws IOException if the record cannot be written
	 */
	public static void write(JsonMapper json, Path destination, ObjectNode contents) throws IOException
	{
		Path parent = parent(destination);
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".cat-record-", ".json");
		try
		{
			json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), contents);
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * Atomically writes a text record.
	 *
	 * @param destination the record path
	 * @param contents the record contents
	 * @throws IOException if the record cannot be written
	 */
	public static void writeText(Path destination, String contents) throws IOException
	{
		Path parent = parent(destination);
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".cat-record-", ".txt");
		try
		{
			Files.writeString(temporary, contents, StandardCharsets.UTF_8);
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * Runs one record transition while holding its JVM-local and inter-process locks.
	 *
	 * @param recordPath the record path
	 * @param operation the transition to run
	 * @throws IOException if the record cannot be locked or the transition fails
	 */
	public static void withLock(Path recordPath, IoOperation operation) throws IOException
	{
		Path lockPath = recordPath.resolveSibling(recordPath.getFileName() + ".lock");
		Files.createDirectories(parent(lockPath));
		Object monitor = LOCKS[Math.floorMod(lockPath.hashCode(), LOCKS.length)];
		synchronized (monitor)
		{
			try (FileChannel channel = FileChannel.open(lockPath, java.nio.file.StandardOpenOption.CREATE,
				java.nio.file.StandardOpenOption.WRITE); FileLock lock = channel.lock())
			{
				if (!lock.isValid())
					throw new IOException("Unable to lock evaluation record: " + recordPath);
				operation.run();
			}
		}
	}

	/**
	 * Updates one JSON object while owning its JVM-local and inter-process record locks.
	 *
	 * @param json the JSON codec
	 * @param recordPath the record path
	 * @param mutation the state change, which returns whether a new record must be written
	 * @throws IOException if the record cannot be read, locked, updated, or written
	 */
	public static void update(JsonMapper json, Path recordPath, RecordMutation mutation) throws IOException
	{
		Path normalizedRecordPath = recordPath.toAbsolutePath().normalize();
		withLock(normalizedRecordPath, () ->
		{
			JsonNode parsed = read(json, normalizedRecordPath);
			if (!parsed.isObject())
				throw new IOException("Expected JSON object record: " + normalizedRecordPath);
			ObjectNode record = (ObjectNode) parsed;
			if (mutation.apply(record))
				write(json, normalizedRecordPath, record);
		});
	}

	/**
	 * Resolves the parent directory where an atomic replacement can be staged.
	 *
	 * @param path the record path
	 * @return its normalized parent directory
	 * @throws IOException if the path has no parent
	 */
	private static Path parent(Path path) throws IOException
	{
		Path parent = path.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Output has no parent directory: " + path);
		return parent;
	}

	/**
	 * Creates the JVM-local guards used before acquiring file locks.
	 *
	 * @return the guards
	 */
	private static Object[] createLocks()
	{
		Object[] result = new Object[64];
		for (int index = 0; index < result.length; ++index)
			result[index] = new Object();
		return result;
	}

	/**
	 * Performs one locked record mutation.
	 */
	@FunctionalInterface
	public interface IoOperation
	{
		/**
		 * Runs the locked transition.
		 *
		 * @throws IOException if the transition fails
		 */
		void run() throws IOException;
	}

	/**
	 * Changes one locked JSON object.
	 */
	@FunctionalInterface
	public interface RecordMutation
	{
		/**
		 * Changes the locked record.
		 *
		 * @param record the mutable record
		 * @return whether the changed record must be written
		 * @throws IOException if the mutation cannot preserve the record contract
		 */
		boolean apply(ObjectNode record) throws IOException;
	}

	/**
	 * Waits briefly for an atomically replaced record to appear.
	 *
	 * @param path the record path
	 * @throws IOException if the wait is interrupted
	 */
	private static void waitForAppearance(Path path) throws IOException
	{
		try
		{
			Thread.sleep(RECORD_APPEARANCE_WAIT_MILLIS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for evaluation record: " + path, e);
		}
	}
}
