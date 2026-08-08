package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps an execution's isolated directory locked while its owning runner process is active.
 */
public final class HarnessRunLock implements AutoCloseable
{
	private static final HarnessRunLock UNLOCKED = new HarnessRunLock(null, null);

	/**
	 * Returns a no-op lease for callers that do not own a manager-backed run.
	 *
	 * @return the no-op lease
	 */
	public static HarnessRunLock unlocked()
	{
		return UNLOCKED;
	}

	/**
	 * Acquires an exclusive lock on an existing run lock file.
	 *
	 * @param file the regular run lock file
	 * @return the acquired lock
	 * @throws IOException if the file cannot be locked or is already locked
	 */
	public static HarnessRunLock acquire(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		FileChannel channel = FileChannel.open(file, WRITE);
		try
		{
			FileLock lock = channel.tryLock();
			if (lock == null)
				throw new IOException("Run is already active: " + file.getParent());
			return new HarnessRunLock(channel, lock);
		}
		catch (OverlappingFileLockException e)
		{
			channel.close();
			throw new IOException("Run is already active: " + file.getParent(), e);
		}
		catch (IOException e)
		{
			channel.close();
			throw e;
		}
	}

	private final FileChannel channel;
	private final FileLock lock;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Creates a lock lease from an acquired channel lock.
	 *
	 * @param channel the lock channel
	 * @param lock the acquired lock, or {@code null} for the no-op lease
	 */
	private HarnessRunLock(FileChannel channel, FileLock lock)
	{
		this.channel = channel;
		this.lock = lock;
	}

	/**
	 * Returns whether this lease currently holds an exclusive file lock.
	 *
	 * @return true if this lease holds an exclusive file lock
	 */
	public boolean isHeld()
	{
		return lock != null && lock.isValid() && !closed.get();
	}

	/**
	 * Releases the owned lock and closes its channel.
	 *
	 * @throws IOException if release or closure fails
	 */
	@Override
	public void close() throws IOException
	{
		if (!closed.compareAndSet(false, true))
			return;
		if (lock == null)
			return;
		IOException failure = null;
		try
		{
			lock.release();
		}
		catch (IOException e)
		{
			failure = e;
		}
		try
		{
			channel.close();
		}
		catch (IOException e)
		{
			if (failure == null)
				failure = e;
			else
				failure.addSuppressed(e);
		}
		if (failure != null)
			throw failure;
	}
}
