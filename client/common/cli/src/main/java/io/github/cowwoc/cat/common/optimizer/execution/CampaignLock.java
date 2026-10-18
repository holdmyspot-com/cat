package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.cowwoc.cat.common.ExclusiveFileLock;
import io.github.cowwoc.cat.common.LockFileOpener;
import io.github.cowwoc.cat.common.PortableFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The exclusive right to write one campaign, held by one {@link Driver} at a time.
 * <p>
 * The lock is an operating-system file lock, so it is released when its process ends, however the process ends. While
 * a driver in this JVM holds it, another attempt in this JVM does not open the lock file, because closing that file
 * would release the lock for other processes (see {@link ExclusiveFileLock}).
 * <p>
 * A driver that {@link #acquire(Path, LockHolder, JsonMapper) acquires} the lock records its host session and process
 * in a file next to the lock file, because Windows forbids reading a file that another process has locked. The record
 * is deleted before the lock is released. A crash leaves it behind, and the next holder replaces it.
 */
public final class CampaignLock implements AutoCloseable
{
	private static final String HOLDER_SUFFIX = ".holder";
	private static final String TEMPORARY_SUFFIX = ".tmp";
	private final ExclusiveFileLock lock;
	/**
	 * The record of the lock's holder, or {@code null} if the holder is not recorded.
	 */
	private final Path record;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Creates a held lock.
	 *
	 * @param lock the lock on the campaign's lock file
	 * @param record the record of the lock's holder, or {@code null} if the holder is not recorded
	 */
	private CampaignLock(ExclusiveFileLock lock, Path record)
	{
		this.lock = lock;
		this.record = record;
	}

	/**
	 * Acquires the lock for a driver, recording the driver's host session and process.
	 * <p>
	 * When another driver holds the lock, the outcome depends on its record:
	 * <ul>
	 *   <li>If it belongs to another host session, or no readable record exists, the lock is not acquired.</li>
	 *   <li>If it belongs to the same host session and its process is neither the current process nor one that
	 *     started it, this method waits until the lock is released and then acquires it. A host session runs one
	 *     command after another, so its earlier command is expected to end.</li>
	 *   <li>Otherwise, the holder ends only after the caller does, so this method fails instead of waiting.</li>
	 * </ul>
	 *
	 * @param file the campaign's lock file, which is created if needed
	 * @param holder the host session and process of the driver
	 * @param json reads and writes the record of the lock's holder
	 * @return the lock, or empty if a driver of another host session, or one whose session is unknown, holds it
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalStateException if a driver of the same host session holds the lock in the current process or in
	 *   a process that started it
	 * @throws IOException if the lock directory cannot be created, the file cannot be opened, locking fails, or the
	 *   record of the holder cannot be written
	 * @throws InterruptedException if the thread is interrupted while it waits for the lock
	 */
	public static Optional<CampaignLock> acquire(Path file, LockHolder holder, JsonMapper json)
		throws IOException, InterruptedException
	{
		requireThat(file, "file").isNotNull();
		requireThat(holder, "holder").isNotNull();
		requireThat(json, "json").isNotNull();
		createDirectory(file);
		LockFileOpener opener = CampaignLock::open;
		Path record = file.resolveSibling(file.getFileName() + HOLDER_SUFFIX);
		Optional<ExclusiveFileLock> lock = ExclusiveFileLock.tryLock(file, opener);
		if (lock.isEmpty())
		{
			Optional<LockHolder> current = read(record, json);
			if (current.isEmpty() || !current.get().session().equals(holder.session()))
				return Optional.empty();
			if (current.get().isCurrentProcessOrAncestor())
			{
				throw new IllegalStateException("Process " + current.get().pid() + " of session " + holder.session() +
					" holds the lock " + file + ". It is the current process or one that started it, so it cannot " +
					"release the lock before the current process ends.");
			}
			lock = Optional.of(ExclusiveFileLock.lock(file, opener));
		}

		try
		{
			write(record, holder, json);
		}
		catch (IOException | RuntimeException e)
		{
			try (ExclusiveFileLock _ = lock.get())
			{
				throw e;
			}
		}
		return Optional.of(new CampaignLock(lock.get(), record));
	}

	/**
	 * Returns the holder that a record names.
	 *
	 * @param record the record of the lock's holder
	 * @param json reads the record
	 * @return the holder, or empty if the record does not exist or is malformed
	 * @throws IOException if the record exists but cannot be read
	 */
	private static Optional<LockHolder> read(Path record, JsonMapper json) throws IOException
	{
		JsonNode root;
		try
		{
			root = json.readTree(Files.readAllBytes(record));
		}
		catch (NoSuchFileException | JacksonException _)
		{
			return Optional.empty();
		}
		if (root == null || !root.isObject())
			return Optional.empty();
		JsonNode session = root.get("session_id");
		JsonNode pid = root.get("pid");
		JsonNode startedAt = root.get("started_at");
		if (session == null || !session.isString() || session.asString().isBlank() || pid == null ||
			!pid.isIntegralNumber() || !pid.canConvertToLong() || pid.asLong() <= 0)
		{
			return Optional.empty();
		}
		Instant start = null;
		if (startedAt != null && !startedAt.isNull())
		{
			if (!startedAt.isString())
				return Optional.empty();
			try
			{
				start = Instant.parse(startedAt.asString());
			}
			catch (DateTimeParseException _)
			{
				return Optional.empty();
			}
		}
		return Optional.of(new LockHolder(session.asString(), pid.asLong(), start));
	}

	/**
	 * Replaces the record of the lock's holder.
	 *
	 * @param record the record
	 * @param holder the holder
	 * @param json writes the record
	 * @throws IOException if the record cannot be written
	 */
	private static void write(Path record, LockHolder holder, JsonMapper json) throws IOException
	{
		ObjectNode root = json.createObjectNode();
		root.put("session_id", holder.session());
		root.put("pid", holder.pid());
		if (holder.startedAt() == null)
			root.putNull("started_at");
		else
			root.put("started_at", holder.startedAt().toString());
		Path temporary = record.resolveSibling(record.getFileName() + TEMPORARY_SUFFIX);
		Files.write(temporary, json.writeValueAsBytes(root));
		PortableFiles.replace(temporary, record);
	}

	/**
	 * Acquires the lock without waiting.
	 *
	 * @param file the campaign's lock file, which is created if needed
	 * @return the lock, or empty if another driver holds it
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException if the lock directory cannot be created, the file cannot be opened, or locking fails
	 */
	public static Optional<CampaignLock> tryAcquire(Path file) throws IOException
	{
		return tryAcquire(file, CampaignLock::open);
	}

	/**
	 * Opens a lock file, creating it if needed.
	 *
	 * @param file the lock file
	 * @return the open file
	 * @throws IOException if the file cannot be opened
	 */
	private static FileChannel open(Path file) throws IOException
	{
		return FileChannel.open(file, CREATE, WRITE);
	}

	/**
	 * Creates the directory of a lock file and its missing ancestors.
	 *
	 * @param file the lock file
	 * @throws IOException if a directory cannot be created
	 */
	private static void createDirectory(Path file) throws IOException
	{
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);
	}

	/**
	 * Acquires the lock without waiting, opening its file through a given opener.
	 * <p>
	 * The opened channel is closed unless the returned lock holds it.
	 *
	 * @param file the campaign's lock file
	 * @param opener opens the lock file, creating it if needed
	 * @return the lock, or empty if another driver holds it
	 * @throws NullPointerException if {@code file} or {@code opener} are null
	 * @throws IOException if the lock directory cannot be created, the file cannot be opened, or locking or closing the
	 *   channel fails
	 */
	public static Optional<CampaignLock> tryAcquire(Path file, LockFileOpener opener) throws IOException
	{
		requireThat(file, "file").isNotNull();
		requireThat(opener, "opener").isNotNull();
		createDirectory(file);
		return ExclusiveFileLock.tryLock(file, opener).map(lock -> new CampaignLock(lock, null));
	}

	/**
	 * Deletes the record of the lock's holder, then releases the lock. Releasing it again has no effect.
	 *
	 * @throws IOException if the record cannot be deleted, or the lock cannot be released or its file cannot be closed.
	 *   The lock is released even if the record cannot be deleted.
	 */
	@Override
	public void close() throws IOException
	{
		if (!closed.compareAndSet(false, true))
			return;
		try (lock)
		{
			if (record != null)
				Files.deleteIfExists(record);
		}
	}
}
