package io.github.cowwoc.cat.common.optimizer.state;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Holds workflow and campaign file locks across nested validation on the same thread.
 */
final class CampaignFileLocks
{
	private static final ThreadLocal<Set<Path>> HELD = new ThreadLocal<>();

	/**
	 * Prevents construction.
	 */
	private CampaignFileLocks()
	{
	}

	/**
	 * Runs an operation under the named process and file lock, reusing a lock already held by this thread.
	 *
	 * @param <T> result type
	 * @param lockFile exact lock file in a pinned directory
	 * @param operation operation protected by this lock
	 * @return operation result
	 * @throws IOException if the lock or operation fails
	 */
	static <T> T withLock(Path lockFile, IoOperation<T> operation) throws IOException
	{
		Objects.requireNonNull(lockFile, "lockFile");
		Objects.requireNonNull(operation, "operation");
		synchronized (CampaignFileLocks.class)
		{
			Set<Path> held = HELD.get();
			if (held != null && held.contains(lockFile))
				return operation.run();
			try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
				StandardOpenOption.WRITE); FileLock lock = channel.lock())
			{
				if (!lock.isValid())
					throw new IOException("Campaign file lock is invalid: " + lockFile);
				Set<Path> active = held;
				if (active == null)
				{
					active = new HashSet<>();
					HELD.set(active);
				}
				active.add(lockFile);
				try
				{
					return operation.run();
				}
				finally
				{
					active.remove(lockFile);
					if (active.isEmpty())
						HELD.remove();
				}
			}
		}
	}

	/**
	 * One operation whose checked failure remains visible to its lock owner.
	 *
	 * @param <T> result type
	 */
	@FunctionalInterface
	interface IoOperation<T>
	{
		/**
		 * Runs while the named file lock is held.
		 *
		 * @return operation result
		 * @throws IOException if the operation fails
		 */
		T run() throws IOException;
	}
}
