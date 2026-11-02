package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.LockFileOpener;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The exclusive right to write one campaign, held by one holder at a time.
 * <p>
 * The lock is an operating-system file lock, so it is released when its process ends, however the process ends. While
 * a holder in this JVM holds it, another attempt in this JVM does not open the lock file, because closing that file
 * would release the lock for other processes (see {@link ExclusiveFileLock}).
 * <p>
 * A holder that hands the lock to a {@link Driver} for part of its work {@link #lend() lends} it, so that closing the
 * driver leaves the lock held until the holder releases it.
 */
public final class CampaignLock implements AutoCloseable
{
	private final ExclusiveFileLock lock;
	private final boolean releases;

	/**
	 * Creates a held lock.
	 *
	 * @param lock the lock on the campaign's lock file
	 * @param releases true if closing this object releases the lock, false if it was lent by a holder that releases it
	 */
	private CampaignLock(ExclusiveFileLock lock, boolean releases)
	{
		this.lock = lock;
		this.releases = releases;
	}

	/**
	 * Acquires the lock without waiting.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param file the campaign's lock file, which is created if needed
	 * @return the lock, or empty if another driver holds it
	 * @throws NullPointerException if {@code fileLocks} or {@code file} are null
	 * @throws IOException if the lock directory cannot be created, the file cannot be opened, or locking fails
	 */
	public static Optional<CampaignLock> tryAcquire(FileLocks fileLocks, Path file) throws IOException
	{
		return tryAcquire(fileLocks, file, lockFile -> FileChannel.open(lockFile, CREATE, WRITE));
	}

	/**
	 * Acquires the lock without waiting, opening its file through a given opener.
	 * <p>
	 * The opened channel is closed unless the returned lock holds it.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param file the campaign's lock file
	 * @param opener opens the lock file, creating it if needed
	 * @return the lock, or empty if another driver holds it
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the lock directory cannot be created, the file cannot be opened, or locking or closing the
	 *   channel fails
	 */
	public static Optional<CampaignLock> tryAcquire(FileLocks fileLocks, Path file, LockFileOpener opener)
		throws IOException
	{
		requireThat(fileLocks, "fileLocks").isNotNull();
		requireThat(file, "file").isNotNull();
		requireThat(opener, "opener").isNotNull();
		Path parent = file.toAbsolutePath().getParent();
		if (parent != null)
			Files.createDirectories(parent);
		return fileLocks.tryLock(file, opener).map(held -> new CampaignLock(held, true));
	}

	/**
	 * Returns the same lock for a borrower, such as a {@link Driver}, whose closing does not release it. The lock stays
	 * held until this object is closed.
	 *
	 * @return the lent lock
	 */
	public CampaignLock lend()
	{
		return new CampaignLock(lock, false);
	}

	/**
	 * Releases the lock, unless it was {@link #lend() lent}. Releasing it again has no effect.
	 *
	 * @throws IOException if the lock cannot be released or its file cannot be closed
	 */
	@Override
	public void close() throws IOException
	{
		if (releases)
			lock.close();
	}
}
