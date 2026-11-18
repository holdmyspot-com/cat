package com.holdmyspot.cat.common.installer.engine;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystemException;
import java.time.Duration;
import java.util.List;

/**
 * Provides a bounded retry schedule for transient file-removal failures.
 */
final class DeletionRetry
{
	/**
	 * The sum of the pauses between attempts of one deletion.
	 */
	static final Duration DEADLINE = Duration.ofSeconds(2);
	/**
	 * The finite delay schedule whose durations sum to {@link #DEADLINE}.
	 */
	private static final List<Duration> WAITS = List.of(Duration.ofMillis(10), Duration.ofMillis(20),
		Duration.ofMillis(40), Duration.ofMillis(80), Duration.ofMillis(160), Duration.ofMillis(320),
		Duration.ofMillis(640), Duration.ofMillis(730));
	private final Waiting waiting;
	/**
	 * The index of the next delay, which makes each failure in this deletion share one finite schedule.
	 */
	private int nextWaitIndex;

	/**
	 * Creates one deletion's retry schedule.
	 *
	 * @param waiting creates the pauses between attempts
	 */
	DeletionRetry(Waiting waiting)
	{
		requireThat(waiting, "waiting").isNotNull();
		this.waiting = waiting;
	}

	/**
	 * Waits before retrying an eligible file-removal failure.
	 *
	 * @param failure the failed removal
	 * @return true if another attempt may run, or false when the failure is ineligible, the schedule is exhausted or
	 *         the pause was interrupted
	 * @throws IOException if the waiting operation fails without interrupting the thread
	 */
	boolean pause(IOException failure) throws IOException
	{
		requireThat(failure, "failure").isNotNull();
		boolean canRetry = isRetryableFailure(failure) && nextWaitIndex < WAITS.size();
		if (!canRetry)
			return false;
		Duration wait = WAITS.get(nextWaitIndex);
		nextWaitIndex += 1;
		try
		{
			waiting.every(wait).pause();
		}
		catch (IOException pauseFailure)
		{
			if (Thread.currentThread().isInterrupted())
			{
				if (pauseFailure != failure)
					failure.addSuppressed(pauseFailure);
				return false;
			}
			throw pauseFailure;
		}
		return true;
	}

	/**
	 * Indicates whether the operating system reports the failure type for a transient deletion conflict.
	 *
	 * @param failure the failed removal
	 * @return true for access denied or a non-empty directory everywhere, and other file-system failures on Windows
	 */
	private static boolean isRetryableFailure(IOException failure)
	{
		return failure instanceof AccessDeniedException || failure instanceof DirectoryNotEmptyException ||
			(Platform.current().isWindows() && failure instanceof FileSystemException);
	}
}
