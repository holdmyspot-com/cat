package com.holdmyspot.cat.common.optimizer.execution;

import java.nio.file.NoSuchFileException;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
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
 * cancelling command writes a request addressed to that identity. A later run cannot consume an older request.
 */
public final class BackgroundRun implements AutoCloseable
{
	private final Path lockFile;
	private final String token;
	private boolean closed;

	/**
	 * The process and command start of a driver.
	 *
	 * @param pid the process identifier
	 * @param processStart the process start, which prevents PID reuse
	 * @param started the command start time
	 * @param token the cancellation address
	 */
	public record Owner(long pid, String processStart, Instant started, String token)
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
	public BackgroundRun(Path lockFile, Instant started, JsonMapper json, FileReplacer replacer) throws IOException
	{
		this.lockFile = lockFile;
		this.token = UUID.randomUUID().toString();
		ProcessHandle process = ProcessHandle.current();
		String processStart = HarnessProcesses.system().find(process.pid()).orElseThrow().start().orElseThrow();
		tools.jackson.databind.node.ObjectNode node = json.createObjectNode().put("pid", process.pid()).
			put("processStart", processStart).
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
	 * Returns the live owner, or none after its process died or its command ended.
	 *
	 * @param lockFile the campaign lock file
	 * @param json the state mapper
	 * @return the live owner
	 * @throws IOException if state cannot be read
	 */
	public static Optional<Owner> owner(Path lockFile, JsonMapper json) throws IOException
	{
		Path file = ownerFile(lockFile);
		if (!Files.isRegularFile(file))
			return Optional.empty();
		Owner owner;
		try
		{
			tools.jackson.databind.JsonNode node = json.readTree(Files.readString(file));
			owner = new Owner(node.path("pid").asLong(), node.path("processStart").asString(),
				Instant.parse(node.path("started").asString()), node.path("token").asString());
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		return HarnessProcesses.system().find(owner.pid()).
			filter(process -> process.start().equals(Optional.of(owner.processStart()))).map(_ -> owner);
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
		Optional<Owner> owner = owner(lockFile, json);
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
