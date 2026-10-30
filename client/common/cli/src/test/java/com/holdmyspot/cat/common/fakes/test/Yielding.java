package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;

import java.time.Clock;

/**
 * Creates the retrying collaborators that tests pass to the code they exercise. Each yields the processor between
 * attempts instead of sleeping, and measures its limit with the system clock, so a transient refusal is retried until
 * it clears, as in production.
 */
public final class Yielding
{
	/**
	 * Returns a file replacer for the current platform.
	 * <p>
	 * On Windows, a replacement that another thread's reader blocks is retried until the reader closes the file.
	 *
	 * @return the replacer
	 */
	public static FileReplacer fileReplacer()
	{
		return new FileReplacer(Platform.current(), Thread::yield, Clock.systemUTC());
	}

	/**
	 * Returns waiting whose pauses yield the processor instead of sleeping, whatever their interval.
	 *
	 * @return the waiting
	 */
	public static Waiting waiting()
	{
		return _ -> Thread::yield;
	}

	/**
	 * Returns an executable-file runner.
	 * <p>
	 * A start that Linux refuses because another thread's child process still holds the file open is retried until the
	 * child closes it.
	 *
	 * @return the runner
	 */
	public static ExecutableFiles executableFiles()
	{
		return new ExecutableFiles(Thread::yield, Clock.systemUTC());
	}

	/**
	 * Prevents construction.
	 */
	private Yielding()
	{
	}
}
