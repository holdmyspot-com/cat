package io.github.cowwoc.cat.common.runner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;


/**
 * Defines the shared ceiling for concurrently running CAT harness instances.
 */
public final class HarnessConcurrencyLimit implements AutoCloseable
{
	/**
	 * The highest number of harness instances CAT may run at once.
	 */
	public static final int DEFAULT_MAXIMUM_CONCURRENCY = 16;
	private static final long RETRY_WAIT_MILLIS = 100;
	private final FileChannel channel;
	private final FileLock lock;

	/**
	 * Acquires one of the configured cross-harness capacity slots.
	 *
	 * @param maximumConcurrency the shared maximum number of harness instances
	 *
	 * @return the held capacity slot
	 *
	 * @throws IOException if a capacity slot cannot be acquired
	 */
	public static HarnessConcurrencyLimit acquire(int maximumConcurrency) throws IOException
	{
		if (maximumConcurrency < 1 || maximumConcurrency > DEFAULT_MAXIMUM_CONCURRENCY)
			throw new IOException("Harness concurrency must be between 1 and " + DEFAULT_MAXIMUM_CONCURRENCY);
		Path directory = lockDirectory();
		Files.createDirectories(directory);
		while (true)
		{
			for (int slot = 0; slot < maximumConcurrency; ++slot)
			{
				FileChannel channel = FileChannel.open(directory.resolve("slot-" + slot + ".lock"), CREATE, WRITE);
				try
				{
					FileLock lock = channel.tryLock();
					if (lock != null)
						return new HarnessConcurrencyLimit(channel, lock);
				}
				catch (OverlappingFileLockException _)
				{
				}
				channel.close();
			}
			waitForCapacity();
		}
	}

	/**
	 * Resolves the durable global lock namespace rather than creating coordination state in an invocation's temporary
	 * directory.
	 *
	 * @return the directory that contains shared capacity locks
	 */
	private static Path lockDirectory()
	{
		return Path.of(System.getProperty("user.home"), ".cat/locks/harness-capacity").toAbsolutePath().normalize();
	}

	/**
	 * Waits for another harness process to release a capacity slot.
	 *
	 * @throws IOException if the wait is interrupted
	 */
	private static void waitForCapacity() throws IOException
	{
		try
		{
			Thread.sleep(RETRY_WAIT_MILLIS);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for harness capacity", e);
		}
	}

	/**
	 * Creates one acquired capacity slot.
	 *
	 * @param channel the open slot channel
	 *
	 * @param lock the held slot lock
	 */
	private HarnessConcurrencyLimit(FileChannel channel, FileLock lock)
	{
		this.channel = channel;
		this.lock = lock;
	}

	/**
	 * Verifies this object still owns its capacity slot.
	 *
	 * @throws IOException if the capacity slot was lost
	 */
	public void verifyHeld() throws IOException
	{
		if (!lock.isValid())
			throw new IOException("Harness-capacity slot was lost");
	}

	/**
	 * Releases the capacity slot.
	 *
	 * @throws IOException if the slot cannot be released
	 */
	@Override
	public void close() throws IOException
	{
		lock.release();
		channel.close();
	}
}
