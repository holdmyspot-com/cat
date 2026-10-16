package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.ExclusiveFileLock;
import io.github.cowwoc.cat.common.LockFileOpener;
import io.github.cowwoc.cat.common.secrets.HarnessRunLockAccess;
import io.github.cowwoc.cat.common.secrets.HarnessRunLockSecrets;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Optional;

import static java.nio.file.StandardOpenOption.WRITE;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Keeps an execution's isolated directory locked while its owning runner process is active.
 * <p>
 * While this JVM holds a run's lock, another attempt in this JVM does not open the lock file, because closing that file
 * would release the lock for other processes (see {@link ExclusiveFileLock}).
 */
public final class HarnessRunLock implements AutoCloseable
{
	static
	{
		HarnessRunLockSecrets.setAccess(new HarnessRunLockAccess()
		{
			@Override
			public HarnessRunLock acquire(Path file, LockFileOpener opener) throws IOException
			{
				return HarnessRunLock.acquire(file, opener);
			}
		});
	}

	private static final HarnessRunLock UNLOCKED = new HarnessRunLock(null);

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
	 *
	 * @return the acquired lock
	 *
	 * @throws IOException if the file cannot be locked or is already locked
	 */
	public static HarnessRunLock acquire(Path file) throws IOException
	{
		return acquire(file, lockFile -> FileChannel.open(lockFile, WRITE));
	}

	/**
	 * Acquires an exclusive lock on an existing run lock file, opening it through a given opener.
	 *
	 * @param file the regular run lock file
	 * @param opener opens the run lock file
	 *
	 * @return the acquired lock
	 *
	 * @throws IOException if the file cannot be locked or is already locked
	 */
	static HarnessRunLock acquire(Path file, LockFileOpener opener) throws IOException
	{
		requireThat(file, "file").isNotNull();
		requireThat(opener, "opener").isNotNull();
		Optional<ExclusiveFileLock> lock = ExclusiveFileLock.tryLock(file, opener);
		if (lock.isEmpty())
			throw new IOException("Run is already active: " + file.getParent());
		return new HarnessRunLock(lock.get());
	}

	private final ExclusiveFileLock lock;

	/**
	 * Creates a lock lease.
	 *
	 * @param lock the lock on the run lock file, or {@code null} for the no-op lease
	 */
	private HarnessRunLock(ExclusiveFileLock lock)
	{
		this.lock = lock;
	}

	/**
	 * Indicates whether this lease currently holds an exclusive file lock.
	 *
	 * @return true if this lease holds an exclusive file lock
	 */
	public boolean isHeld()
	{
		return lock != null && lock.isValid();
	}

	/**
	 * Releases the owned lock and closes its channel. Closing the lease again has no effect.
	 *
	 * @throws IOException if release or closure fails
	 */
	@Override
	public void close() throws IOException
	{
		if (lock != null)
			lock.close();
	}
}
