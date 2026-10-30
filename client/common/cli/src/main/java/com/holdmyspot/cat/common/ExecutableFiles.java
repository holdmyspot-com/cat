package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.secrets.ExecutableFilesAccess;
import com.holdmyspot.cat.common.secrets.ExecutableFilesSecrets;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Runs local executable files when a caller must execute generated or recently replaced content.
 * <p>
 * While a JVM writes an executable file, a child process that another thread starts inherits the open file until the
 * child closes its inherited descriptors. Until then, Linux refuses to run the file and reports {@code ETXTBSY}
 * ("Text file busy"), whether the JVM or another process, such as a shell, tries to run it. This boundary retries only
 * that transient start failure, until {@link #RETRY_LIMIT} passes, when it starts the file itself; it does not own file
 * writing. A file that a separate process writes cannot be inherited this way.
 * <p>
 * This class is thread-safe.
 *
 * @see <a href="https://bugs.openjdk.org/browse/JDK-8068370">JDK-8068370</a>.
 */
public final class ExecutableFiles
{
	static
	{
		ExecutableFilesSecrets.setExecutableFilesAccess(new ExecutableFilesAccess()
		{
			@Override
			public Process run(ExecutableFiles executables, ExecutableFiles.ProcessStart start) throws IOException
			{
				return executables.run(start);
			}

			@Override
			public ExecutableFiles create(Pause pause, Clock clock, ProcessLauncher launcher)
			{
				return new ExecutableFiles(pause, clock, launcher);
			}
		});
	}

	/**
	 * How long a start keeps trying while the file is busy.
	 */
	private static final Duration RETRY_LIMIT = Duration.ofSeconds(1);
	/**
	 * How long {@link #retrying(Waiting)}'s instance waits between attempts.
	 */
	private static final Duration RETRY_INTERVAL = Duration.ofMillis(25);
	private final Pause pause;
	private final Clock clock;
	private final ProcessLauncher launcher;

	/**
	 * Returns an instance that waits 25 milliseconds between attempts and measures its limit with the system clock.
	 *
	 * @param waiting how the instance waits between attempts
	 * @return the instance
	 * @throws NullPointerException if {@code waiting} is null
	 */
	public static ExecutableFiles retrying(Waiting waiting)
	{
		requireThat(waiting, "waiting").isNotNull();
		return new ExecutableFiles(waiting.every(RETRY_INTERVAL), Clock.systemUTC());
	}

	/**
	 * Creates an instance.
	 *
	 * @param pause waits between attempts
	 * @param clock measures how long a start has been trying
	 * @throws NullPointerException if any of the arguments are null
	 */
	public ExecutableFiles(Pause pause, Clock clock)
	{
		this(pause, clock, ProcessBuilder::start);
	}

	/**
	 * Creates an instance with a controlled process launcher.
	 *
	 * @param pause    waits between attempts
	 * @param clock    measures how long a start has been trying
	 * @param launcher starts the configured process on each attempt
	 * @throws NullPointerException if any of the arguments are null
	 */
	private ExecutableFiles(Pause pause, Clock clock, ProcessLauncher launcher)
	{
		requireThat(pause, "pause").isNotNull();
		requireThat(clock, "clock").isNotNull();
		requireThat(launcher, "launcher").isNotNull();

		this.pause = pause;
		this.clock = clock;
		this.launcher = launcher;
	}

	/**
	 * Runs a configured executable process, retrying only a transient text-file-busy failure.
	 *
	 * @param builder the configured process builder
	 * @return the started process
	 * @throws NullPointerException if {@code builder} is null
	 * @throws IOException          if the process cannot be started, or the pause between attempts fails
	 */
	public Process run(ProcessBuilder builder) throws IOException
	{
		requireThat(builder, "builder").isNotNull();
		return run(() -> launcher.start(builder));
	}

	/**
	 * Runs an executable process through one controlled start operation.
	 *
	 * @param start the process-start operation
	 * @return the started process
	 * @throws NullPointerException if {@code start} is null
	 * @throws IOException          if the process cannot be started, or the pause between attempts fails
	 */
	private Process run(ProcessStart start) throws IOException
	{
		requireThat(start, "start").isNotNull();
		Instant deadline = clock.instant().plus(RETRY_LIMIT);
		while (true)
		{
			try
			{
				return start.start();
			}
			catch (IOException failure)
			{
				// WORKAROUND: https://bugs.openjdk.org/browse/JDK-8068370
				if (!isTextFileBusy(failure) || !clock.instant().isBefore(deadline))
					throw failure;
				try
				{
					pause.pause();
				}
				catch (IOException pauseFailure)
				{
					pauseFailure.addSuppressed(failure);
					throw pauseFailure;
				}
			}
		}
	}

	/**
	 * Indicates whether a process-start failure reports the POSIX {@code ETXTBSY} condition.
	 *
	 * @param failure the process-start failure
	 * @return {@code true} if the operating-system diagnostic identifies a busy executable
	 */
	private static boolean isTextFileBusy(IOException failure)
	{
		String message = failure.getMessage();
		return message != null && (message.contains("ETXTBSY") || message.contains("Text file busy"));
	}

	/**
	 * Starts a configured process on one attempt.
	 */
	@FunctionalInterface
	public interface ProcessLauncher
	{
		/**
		 * Starts the configured process.
		 *
		 * @param builder the configured process builder
		 * @return the started process
		 * @throws IOException if the process cannot be started
		 */
		Process start(ProcessBuilder builder) throws IOException;
	}

	/**
	 * Starts one process for a retry-controlled executable boundary.
	 */
	@FunctionalInterface
	public interface ProcessStart
	{
		/**
		 * Starts the process.
		 *
		 * @return the started process
		 * @throws IOException if the process cannot be started
		 */
		Process start() throws IOException;
	}
}
