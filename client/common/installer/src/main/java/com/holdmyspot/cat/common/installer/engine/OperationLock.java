package com.holdmyspot.cat.common.installer.engine;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.installer.engine.internal.LockPause;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Serializes installer operations across threads and processes. All engines in this JVM share the coordinator, so a
 * contending thread never opens and closes the held file and inadvertently releases another thread's OS lock.
 */
final class OperationLock
{
	/**
	 * Reservations for every installer lock in this JVM, retained until the corresponding channel closes.
	 */
	private static final FileLocks LOCKS = new FileLocks();
	/**
	 * How long an operation waits for the harness lock while another operation holds it.
	 */
	static final Duration WAIT = Duration.ofMinutes(10);

	/**
	 * Prevents instantiation.
	 */
	private OperationLock()
	{
	}

	/**
	 * Waits for the harness lock up to the configured deadline. A zero duration makes exactly one attempt.
	 *
	 * @param state the harness's state paths
	 * @param clock the source of deadline instants
	 * @param wait  the maximum wait
	 * @param waiting creates pauses between lock attempts
	 * @return the held lock, or empty when the deadline passes
	 * @throws IOException if creating or opening the lock, or waiting between attempts, fails
	 * @throws InterruptedException if the waiting thread is interrupted
	 */
	static Optional<ExclusiveFileLock> acquire(InstallerState state, Clock clock, Duration wait, Waiting waiting)
		throws IOException, InterruptedException
	{
		StateFiles.createDirectories(state.directory());
		PortableFiles.setMode(state.directory(), StateFiles.DIRECTORY_MODE);
		Instant deadline = clock.instant().plus(wait);
		long started = System.nanoTime();
		while (true)
		{
			ExclusiveFileLock lock = LOCKS.tryLock(state.lock(), OperationLock::open).orElse(null);
			if (lock != null)
				return Optional.of(lock);
			Optional<Duration> pause = LockPause.next(clock.instant(), deadline, wait,
				Duration.ofNanos(System.nanoTime() - started));
			if (pause.isEmpty())
				return Optional.empty();
			try
			{
				waiting.every(pause.get()).pause();
			}
			catch (IOException e)
			{
				if (e.getCause() instanceof InterruptedException interrupted)
					throw interrupted;
				throw e;
			}
		}
	}

	/**
	 * Opens an owner-only lock without following a link.
	 *
	 * @param file the lock path
	 * @return the channel whose owner closes it
	 * @throws IOException if opening the lock fails
	 */
	private static FileChannel open(Path file) throws IOException
	{
		StateFiles.requireNoLinks(file);
		if (PortableFiles.hasModes(file))
		{
			return FileChannel.open(file, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				LinkOption.NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(StateFiles.FILE_MODE));
		}
		return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
	}
}
