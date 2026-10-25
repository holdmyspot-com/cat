package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.LockFileOpener;
import com.holdmyspot.cat.common.secrets.HarnessRunLockAccess;
import com.holdmyspot.cat.common.secrets.HarnessRunLockSecrets;
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
		HarnessRunLockSecrets.setHarnessRunLockAccess(new HarnessRunLockAccess()
		{
			@Override
			public HarnessRunLock acquire(FileLocks fileLocks, Path file, LockFileOpener opener) throws IOException
			{
				return HarnessRunLock.acquire(fileLocks, file, opener);
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
	 * @param fileLocks the process-owned lock coordinator
	 * @param file the regular run lock file
	 *
	 * @return the acquired lock
	 *
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the file cannot be locked or is already locked
	 */
	public static HarnessRunLock acquire(FileLocks fileLocks, Path file) throws IOException
	{
		return acquire(fileLocks, file, lockFile -> FileChannel.open(lockFile, WRITE));
	}

	/**
	 * Acquires an exclusive lock on an existing run lock file, opening it through a given opener.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param file the regular run lock file
	 * @param opener opens the run lock file
	 *
	 * @return the acquired lock
	 *
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the file cannot be locked or is already locked
	 */
	static HarnessRunLock acquire(FileLocks fileLocks, Path file, LockFileOpener opener) throws IOException
	{
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(file, "file").isNotNull();
		requireThat(opener, "opener").isNotNull();
		Optional<ExclusiveFileLock> lock = fileLocks.tryLock(file, opener);
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
