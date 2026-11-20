package com.holdmyspot.cat.common.optimizer.execution;

import java.nio.file.NoSuchFileException;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.FileReplacer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Durable background driver identity and cancellation mailbox. Only the driver writes its identity, while a
 * cancelling command writes a request addressed to that identity. A later driver cannot consume an older request.
 */
public final class BackgroundDriver implements AutoCloseable
{
	private final Path lockFile;
	private final String token;
	private boolean closed;

	/**
	 * The identity that a driver records. It names no process: a process identifier is meaningful only inside the
	 * process namespace that assigned it, and a driver in another sandbox has a namespace that its readers cannot see.
	 *
	 * @param started the command start time
	 * @param token the cancellation address
	 */
	public record Owner(Instant started, String token)
	{
	}

	/**
	 * Records the driver while its campaign lock is held.
	 *
	 * @param lockFile the held lock's file
	 * @param started the command start
	 * @param json the state mapper
	 * @param replacer atomically replaces state files
	 * @throws IOException if state cannot be stored
	 */
	public BackgroundDriver(Path lockFile, Instant started, JsonMapper json, FileReplacer replacer) throws IOException
	{
		this.lockFile = lockFile;
		this.token = UUID.randomUUID().toString();
		tools.jackson.databind.node.ObjectNode node = json.createObjectNode().
			put("started", started.toString()).put("token", token);
		write(ownerFile(lockFile), json.writeValueAsString(node), replacer);
	}

	/**
	 * Atomically writes a campaign control file.
	 *
	 * @param file the destination
	 * @param text the content
	 * @param replacer replaces the destination
	 * @throws IOException if the file cannot be written
	 */
	public static void write(Path file, String text, FileReplacer replacer) throws IOException
	{
		Files.createDirectories(file.getParent());
		Path temporary = Files.createTempFile(file.getParent(), ".background-", ".tmp");
		try
		{
			Files.writeString(temporary, text);
			replacer.replace(temporary, file);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Returns the identity file beside the lock.
	 *
	 * @param lockFile the campaign lock file
	 * @return the identity file
	 */
	private static Path ownerFile(Path lockFile)
	{
		return lockFile.resolveSibling("background-driver.json");
	}

	/**
	 * Returns the driver that is alive, which is the one whose identity is recorded while its campaign lock is held.
	 * The lock, not a process identifier, says whether the driver is alive, so the answer is the same for a reader in
	 * any process namespace. The lock is taken and released at once, so a lock that nobody held is left free.
	 *
	 * @param lockFile the campaign lock file
	 * @param fileLocks the process-owned lock coordinator
	 * @param json the state mapper
	 * @return the live owner, or empty if no identity is recorded or no process holds the lock
	 * @throws IOException if state cannot be read or the lock cannot be tried
	 */
	public static Optional<Owner> owner(Path lockFile, FileLocks fileLocks, JsonMapper json) throws IOException
	{
		Optional<Owner> recorded = recorded(lockFile, json);
		if (recorded.isEmpty())
			return recorded;
		Optional<CampaignLock> free = CampaignLock.tryAcquire(fileLocks, lockFile);
		if (free.isEmpty())
			return recorded;
		free.get().close();
		return Optional.empty();
	}

	/**
	 * Returns the driver identity that is recorded, whether or not that driver is still alive.
	 *
	 * @param lockFile the campaign lock file
	 * @param json the state mapper
	 * @return the recorded identity, or empty if none is recorded
	 * @throws IOException if state cannot be read
	 */
	public static Optional<Owner> recorded(Path lockFile, JsonMapper json) throws IOException
	{
		try
		{
			tools.jackson.databind.JsonNode node = json.readTree(Files.readString(ownerFile(lockFile)));
			return Optional.of(new Owner(Instant.parse(node.path("started").asString()),
				node.path("token").asString()));
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Removes the identity that an earlier driver left. The caller holds the campaign lock, so the identity cannot
	 * belong to a live driver.
	 *
	 * @param lockFile the held campaign lock file
	 * @throws IOException if the identity cannot be removed
	 */
	public static void discardStale(Path lockFile) throws IOException
	{
		Files.deleteIfExists(ownerFile(lockFile));
	}

	/**
	 * Requests cancellation of this particular driver.
	 *
	 * @param lockFile the campaign lock file
	 * @param owner the driver to stop
	 * @param replacer atomically replaces the mailbox
	 * @throws IOException if the request cannot be stored
	 */
	public static void requestStop(Path lockFile, Owner owner, FileReplacer replacer) throws IOException
	{
		write(stopFile(lockFile), owner.token(), replacer);
	}

	/**
	 * Returns whether the current driver was asked to stop.
	 *
	 * @param lockFile the campaign lock file
	 * @param json the state mapper
	 * @return whether cancellation was requested
	 * @throws IOException if the mailbox cannot be read
	 */
	public static boolean stopRequested(Path lockFile, JsonMapper json) throws IOException
	{
		Optional<Owner> owner = recorded(lockFile, json);
		if (owner.isEmpty())
			return false;
		try
		{
			return Files.readString(stopFile(lockFile)).equals(owner.get().token());
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
	}

	/**
	 * Returns the cancellation mailbox.
	 *
	 * @param lockFile the campaign lock file
	 * @return the mailbox
	 */
	private static Path stopFile(Path lockFile)
	{
		return lockFile.resolveSibling("stop-request");
	}

	/**
	 * Removes the identity while the driver still holds its lock, after it has stored its result.
	 *
	 * @throws IOException if the identity cannot be removed
	 */
	@Override
	public void close() throws IOException
	{
		if (closed)
			return;
		Path file = ownerFile(lockFile);
		if (Files.isRegularFile(file) && Files.readString(file).contains(token))
			Files.deleteIfExists(file);
		closed = true;
	}
}
