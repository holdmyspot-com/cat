package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The files through which a request made inside a run streams its records to the run's runner after the request's
 * archive, so that a conversation's later turns and interrupts reach the requested session.
 * <p>
 * The sender holds a lock on {@code <id>.lock} in the run's requests directory from before it publishes the archive
 * until it ends, so the runner learns that the sender ended, however it ended, when the lock is free. It publishes
 * each record as {@code <id>.turn-<n>.json}, numbered from 1, by renaming a finished file into place. The runner takes
 * each file in turn without following a link, refusing a file with another name, and deletes it.
 */
final class StreamedRequest
{
	/**
	 * The manifest field that marks a request whose records follow its archive.
	 */
	static final String INPUT_FIELD = "input";
	/**
	 * The value of {@link #INPUT_FIELD} for a streamed request.
	 */
	static final String STREAM = "stream";
	/**
	 * The record types that a streamed request may send.
	 */
	private static final Set<String> TYPES = Set.of("turn", "interrupt", "complete");

	/**
	 * Returns the name of the lock that a request's sender holds while it runs.
	 *
	 * @param id the request identifier
	 * @return the file name
	 */
	static String lockFileName(String id)
	{
		return id + ".lock";
	}

	/**
	 * Returns the name of one record of a request.
	 *
	 * @param id   the request identifier
	 * @param turn the record's number, from 1
	 * @return the file name
	 */
	static String turnFileName(String id, int turn)
	{
		return id + ".turn-" + turn + ".json";
	}

	/**
	 * Indicates whether a request's sender still runs.
	 *
	 * @param requests the run's requests directory
	 * @param id       the request identifier
	 * @return {@code false} if the sender's lock is missing, is not a regular file, or is free
	 * @throws IOException if the lock cannot be checked
	 */
	static boolean senderRuns(Path requests, String id) throws IOException
	{
		Path lock = requests.resolve(lockFileName(id));
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(lock, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
		if (!attributes.isRegularFile())
			return false;
		// Opening for reading and writing never waits for a writer, even if the session swapped in a named pipe
		try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ, StandardOpenOption.WRITE,
			LinkOption.NOFOLLOW_LINKS))
		{
			try (FileLock held = channel.tryLock())
			{
				return held == null;
			}
			catch (OverlappingFileLockException _)
			{
				// The sender runs in this JVM
				return true;
			}
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
	}

	/**
	 * Takes the next record of a request, if its sender has published it.
	 *
	 * @param json      the record mapper
	 * @param requests  the run's requests directory
	 * @param id        the request identifier
	 * @param turn      the record's number, from 1
	 * @param directory a private directory of the runner that receives a copy of the record
	 * @param limits    the bounds that the record must stay within
	 * @return the record, or empty if the sender has not published it
	 * @throws IOException if the record is a link, has another name, exceeds {@code limits}, is not a {@code turn}
	 *                     with a prompt, an {@code interrupt} or the {@code complete} record, or cannot be read
	 */
	static Optional<String> take(JsonMapper json, Path requests, String id, int turn, Path directory,
		RequestLimits limits) throws IOException
	{
		Path published = requests.resolve(turnFileName(id, turn));
		if (!Files.exists(published, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		Path copy = directory.resolve("turn-" + turn + ".json");
		try
		{
			RequestArchive.copy(published, copy, limits);
			Files.delete(published);
			return Optional.of(record(json, Files.readString(copy, UTF_8)));
		}
		finally
		{
			Files.deleteIfExists(copy);
		}
	}

	/**
	 * Validates one record and writes it again, so that the requested session receives only what a record may say.
	 *
	 * @param json the record mapper
	 * @param text the record's file content
	 * @return the record, without its line terminator
	 * @throws IOException if the content is not one record of a type that a streamed request may send
	 */
	private static String record(JsonMapper json, String text) throws IOException
	{
		JsonNode record;
		try
		{
			record = json.readTree(text);
		}
		catch (JacksonException e)
		{
			throw new IOException("A streamed record is malformed", e);
		}
		String type = record.path("type").asString("");
		if (!record.isObject() || !TYPES.contains(type))
			throw new IOException("A streamed record must be a turn, an interrupt or complete");
		if (type.equals("turn"))
		{
			JsonNode prompt = record.path("prompt");
			if (record.size() != 2 || !prompt.isString())
				throw new IOException("A streamed turn holds only its prompt");
			return json.createObjectNode().put("type", type).put("prompt", prompt.asString()).toString();
		}
		if (record.size() != 1)
			throw new IOException("A streamed " + type + " record holds only its type");
		return json.createObjectNode().put("type", type).toString();
	}

	/**
	 * Indicates whether a record ends a request's records.
	 *
	 * @param json   the record mapper
	 * @param record a record that {@link #take} returned
	 * @return {@code true} if it is the {@code complete} record
	 */
	static boolean isComplete(JsonMapper json, String record)
	{
		return json.readTree(record).path("type").asString("").equals("complete");
	}

	/**
	 * The sender of a streamed request: it holds the request's lock and publishes its records.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	static final class Sender implements AutoCloseable
	{
		private final Path requests;
		private final String id;
		private final Path lockFile;
		private final FileChannel channel;
		private final FileLock lock;
		private int sent;

		/**
		 * Creates the request's lock and takes it.
		 *
		 * @param requests the run's requests directory
		 * @param id       the request identifier
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IOException          if the lock cannot be created or taken
		 */
		Sender(Path requests, String id) throws IOException
		{
			requireThat(requests, "requests").isNotNull();
			requireThat(id, "id").isNotNull();
			this.requests = requests;
			this.id = id;
			this.lockFile = requests.resolve(lockFileName(id));
			this.channel = FileChannel.open(lockFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
				StandardOpenOption.WRITE);
			try
			{
				this.lock = channel.lock();
			}
			catch (IOException | RuntimeException e)
			{
				channel.close();
				Files.deleteIfExists(lockFile);
				throw e;
			}
		}

		/**
		 * Publishes the next record.
		 *
		 * @param record the record, without its line terminator
		 * @throws IOException if the record cannot be written
		 */
		void send(String record) throws IOException
		{
			Path temporary = Files.createTempFile(requests, ".turn-", ".json");
			try
			{
				Files.writeString(temporary, record + "\n", UTF_8);
				Files.move(temporary, requests.resolve(turnFileName(id, sent + 1)), StandardCopyOption.ATOMIC_MOVE);
				sent += 1;
			}
			finally
			{
				Files.deleteIfExists(temporary);
			}
		}

		/**
		 * Releases the lock, which tells the run's runner that the sender ended, and removes the records that the
		 * runner did not take.
		 *
		 * @throws IOException if the lock cannot be released or a record cannot be removed
		 */
		@Override
		public void close() throws IOException
		{
			try (channel)
			{
				lock.release();
			}
			finally
			{
				Files.deleteIfExists(lockFile);
				for (int turn = 1; turn <= sent; ++turn)
					Files.deleteIfExists(requests.resolve(turnFileName(id, turn)));
			}
		}
	}

	/**
	 * Prevents construction.
	 */
	private StreamedRequest()
	{
	}
}
