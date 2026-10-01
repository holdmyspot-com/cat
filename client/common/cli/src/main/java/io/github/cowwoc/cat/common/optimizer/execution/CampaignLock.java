package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The exclusive right to write one campaign, held by one {@link Driver} at a time.
 * <p>
 * The lock is an operating-system file lock, so it is released when its process ends, however the process ends.
 */
final class CampaignLock implements AutoCloseable
{
	private final FileChannel channel;
	private final FileLock lock;

	/**
	 * Creates a held lock.
	 *
	 * @param channel the open lock file
	 * @param lock the lock on {@code channel}
	 */
	private CampaignLock(FileChannel channel, FileLock lock)
	{
		this.channel = channel;
		this.lock = lock;
	}

	/**
	 * Acquires the lock without waiting.
	 *
	 * @param file the campaign's lock file, which is created if needed
	 * @return the lock, or empty if another driver holds it
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException if the lock file cannot be opened
	 */
	static Optional<CampaignLock> tryAcquire(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);
		FileChannel channel = FileChannel.open(file, CREATE, WRITE);
		try
		{
			FileLock lock = channel.tryLock();
			if (lock != null)
				return Optional.of(new CampaignLock(channel, lock));
		}
		catch (OverlappingFileLockException _)
		{
			// Another driver in this process holds the lock
		}
		catch (IOException | RuntimeException e)
		{
			channel.close();
			throw e;
		}
		channel.close();
		return Optional.empty();
	}

	/**
	 * Releases the lock.
	 *
	 * @throws IOException if the lock file cannot be closed
	 */
	@Override
	public void close() throws IOException
	{
		try (channel)
		{
			lock.release();
		}
	}
}
