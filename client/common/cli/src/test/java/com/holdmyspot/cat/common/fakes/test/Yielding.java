package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;

import java.io.IOException;
import java.time.Clock;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	 * Starts a program that the test or the code under test may have just written, with the environment that the
	 * builder holds.
	 * <p>
	 * A start that Linux refuses because another thread's child process still holds the program open for writing is
	 * retried until the child closes it, as {@link #executableFiles()} retries it.
	 *
	 * @param builder the configured process builder
	 * @return the started process
	 * @throws NullPointerException if {@code builder} is null
	 * @throws IOException          if the process cannot be started
	 */
	public static Process start(ProcessBuilder builder) throws IOException
	{
		return start(builder, executableFiles());
	}

	/**
	 * Starts a program with the environment that the builder holds, retrying a start that Linux refuses because a
	 * process holds the program open for writing, as {@code executables} retries it.
	 *
	 * @param builder     the configured process builder
	 * @param executables how often and how long a refused start is retried
	 * @return the started process
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the process cannot be started
	 */
	public static Process start(ProcessBuilder builder, ExecutableFiles executables) throws IOException
	{
		requireThat(builder, "builder").isNotNull();
		requireThat(executables, "executables").isNotNull();
		// ExecutableFiles.run(ProcessBuilder) would remove the variables that make a program load code, which some
		// tests set to show that the program under test removes them itself
		return ExecutableFilesSecrets.getExecutableFilesAccess().run(executables, builder::start);
	}

	/**
	 * Prevents construction.
	 */
	private Yielding()
	{
	}
}
