package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Coordinates exclusive file locks for one CAT process. Every lock user in the process shares this object.
 * <p>
 * Reservations use the real path of the lock directory and its file name. A reserved file is not opened again until
 * its lease closes the channel, preventing another channel from releasing the process's operating-system lock.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FileLocks
{
	/**
	 * The files reserved by this coordinator, including attempts that have not finished opening or locking them.
	 */
	private final Set<Path> held = ConcurrentHashMap.newKeySet();
	/**
	 * Serializes blocking lock users before they open a channel on a held file.
	 */
	private final ConcurrentMap<Path, Object> monitors = new ConcurrentHashMap<>();

	/**
	 * Creates a process's lock coordinator.
	 */
	public FileLocks()
	{
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
	public Optional<ExclusiveFileLock> tryLock(Path file, LockFileOpener opener) throws IOException
	{
		requireThat(file, "file").isNotNull();
		requireThat(opener, "opener").isNotNull();
		Path key = keyOf(file);
		if (!held.add(key))
			return Optional.empty();

		FileChannel channel;
		try
		{
			channel = opener.open(file);
		}
		catch (IOException | RuntimeException e)
		{
			held.remove(key);
			throw e;
		}

		try
		{
			FileLock lock = channel.tryLock();
			if (lock != null)
				return Optional.of(new ExclusiveFileLock(channel, lock, () -> held.remove(key)));
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
				held.remove(key);
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
			held.remove(key);
		}
		return Optional.empty();
	}

	/**
	 * Returns the monitor that serializes blocking lock operations on a file in this process.
	 * <p>
	 * Every caller must hold this monitor before opening the file and until its channel closes. Directory aliases
	 * resolve to the same monitor. The monitor belongs to this coordinator for its lifetime.
	 *
	 * @param file the lock file. Its directory must exist.
	 * @return the shared monitor
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException if the file's directory cannot be resolved
	 */
	public Object monitor(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		return monitors.computeIfAbsent(keyOf(file), _ -> new Object());
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
}
