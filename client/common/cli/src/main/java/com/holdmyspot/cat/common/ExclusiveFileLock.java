package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An exclusive operating-system lock on a file, which other processes see until the lock is closed.
 * <p>
 * On some systems, Linux among them, closing any channel on a file releases every lock that the JVM holds on the file,
 * even one taken through another channel, while that lock's {@link FileLock#isValid()} stays {@code true} (see
 * {@link FileLock}). The process-owned {@link FileLocks} coordinator therefore reserves each file before opening it.
 * Every lock user in a process must share that coordinator. A lease releases its reservation only after its channel
 * has closed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ExclusiveFileLock implements AutoCloseable
{
	private final FileChannel channel;
	private final FileLock lock;
	private final Runnable released;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Creates a held lock.
	 *
	 * @param channel the open file
	 * @param lock the lock held on {@code channel}
	 * @param released releases the reservation after the channel closes
	 */
	ExclusiveFileLock(FileChannel channel, FileLock lock, Runnable released)
	{
		requireThat(channel, "channel").isNotNull();
		requireThat(lock, "lock").isNotNull();
		requireThat(released, "released").isNotNull();
		this.channel = channel;
		this.lock = lock;
		this.released = released;
	}

	/**
	 * Indicates whether this lock is held.
	 *
	 * @return {@code true} until the lock is closed or the operating system invalidates it
	 */
	public boolean isValid()
	{
		return !closed.get() && lock.isValid();
	}

	/**
	 * Releases the lock and closes its file. Closing a lock again has no effect.
	 *
	 * @throws IOException if the lock cannot be released or its file cannot be closed. If both fail, the release
	 *   failure is thrown with the close failure suppressed.
	 */
	@Override
	public void close() throws IOException
	{
		if (!closed.compareAndSet(false, true))
			return;
		try (channel)
		{
			lock.release();
		}
		finally
		{
			released.run();
		}
	}
}
