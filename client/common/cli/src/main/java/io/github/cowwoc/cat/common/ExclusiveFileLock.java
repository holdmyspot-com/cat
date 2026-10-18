package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An exclusive operating-system lock on a file, which other processes see until the lock is closed.
 * <p>
 * On some systems, Linux among them, closing any channel on a file releases every lock that the JVM holds on the file,
 * even one taken through another channel, while that lock's {@link FileLock#isValid()} stays {@code true} (see
 * {@link FileLock}). Every lock of this class is therefore recorded in a JVM-wide registry, keyed by the real path of
 * the file's directory and the file's name, and no attempt opens a file that the registry lists. A lock leaves the
 * registry only after its channel has closed.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ExclusiveFileLock implements AutoCloseable
{
	/**
	 * The files that this JVM has locked or is locking, keyed by {@link #keyOf(Path)}. Access is synchronized on the
	 * set, which is notified whenever a key leaves it.
	 */
	private static final Set<Path> HELD = new HashSet<>();
	private final Path key;
	private final FileChannel channel;
	private final FileLock lock;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Creates a held lock.
	 *
	 * @param key the file's registry key
	 * @param channel the open file
	 * @param lock the lock held on {@code channel}
	 */
	private ExclusiveFileLock(Path key, FileChannel channel, FileLock lock)
	{
		this.key = key;
		this.channel = channel;
		this.lock = lock;
	}

	/**
	 * Locks a file without waiting.
	 * <p>
	 * The file is not opened while this JVM holds or is taking a lock on it. Otherwise the opened channel is closed
	 * unless the returned lock holds it.
	 *
	 * @param file the lock file. Its directory must exist.
	 * @param opener opens the file
	 * @return the lock, or empty if this JVM or another process holds a lock on the file
	 * @throws NullPointerException if {@code file} or {@code opener} are null
	 * @throws IOException if the file's directory cannot be resolved, or the file cannot be opened, locked or closed
	 */
	public static Optional<ExclusiveFileLock> tryLock(Path file, LockFileOpener opener) throws IOException
	{
		requireThat(file, "file").isNotNull();
		requireThat(opener, "opener").isNotNull();
		Path key = keyOf(file);
		synchronized (HELD)
		{
			if (!HELD.add(key))
				return Optional.empty();
		}

		FileChannel channel;
		try
		{
			channel = opener.open(file);
		}
		catch (IOException | RuntimeException e)
		{
			release(key);
			throw e;
		}

		try
		{
			FileLock lock = channel.tryLock();
			if (lock != null)
				return Optional.of(new ExclusiveFileLock(key, channel, lock));
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				channel.close();
			}
			catch (IOException | RuntimeException suppressed)
			{
				e.addSuppressed(suppressed);
			}
			finally
			{
				release(key);
			}
			throw e;
		}

		// Another process holds the lock
		try
		{
			channel.close();
		}
		finally
		{
			release(key);
		}
		return Optional.empty();
	}

	/**
	 * Locks a file, waiting while this JVM or another process holds a lock on it.
	 * <p>
	 * The file is not opened while this JVM holds or is taking a lock on it. The opened channel is closed unless the
	 * returned lock holds it.
	 *
	 * @param file the lock file. Its directory must exist.
	 * @param opener opens the file
	 * @return the lock
	 * @throws NullPointerException if {@code file} or {@code opener} are null
	 * @throws IOException if the file's directory cannot be resolved, or the file cannot be opened, locked or closed.
	 *   An interrupt while another process holds the lock closes the channel and throws
	 *   {@link ClosedByInterruptException}.
	 * @throws InterruptedException if the thread is interrupted while this JVM holds the lock
	 */
	public static ExclusiveFileLock lock(Path file, LockFileOpener opener) throws IOException, InterruptedException
	{
		requireThat(file, "file").isNotNull();
		requireThat(opener, "opener").isNotNull();
		Path key = keyOf(file);
		synchronized (HELD)
		{
			while (!HELD.add(key))
				HELD.wait();
		}

		FileChannel channel;
		try
		{
			channel = opener.open(file);
		}
		catch (IOException | RuntimeException e)
		{
			release(key);
			throw e;
		}

		try
		{
			return new ExclusiveFileLock(key, channel, channel.lock());
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				channel.close();
			}
			catch (IOException | RuntimeException suppressed)
			{
				e.addSuppressed(suppressed);
			}
			finally
			{
				release(key);
			}
			throw e;
		}
	}

	/**
	 * Removes a file from the registry and wakes the threads that wait to lock a file.
	 *
	 * @param key the file's registry key
	 */
	private static void release(Path key)
	{
		synchronized (HELD)
		{
			HELD.remove(key);
			HELD.notifyAll();
		}
	}

	/**
	 * Returns the registry key of a file: the real path of its directory, resolved against its name.
	 *
	 * @param file the file
	 * @return the key
	 * @throws IOException if the file's directory cannot be resolved
	 */
	private static Path keyOf(Path file) throws IOException
	{
		Path absolute = file.toAbsolutePath();
		Path directory = absolute.getParent();
		if (directory == null)
			throw new IOException("The lock file " + file + " has no parent directory");
		return directory.toRealPath().resolve(absolute.getFileName());
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
			release(key);
		}
	}
}
