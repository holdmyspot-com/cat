package io.github.cowwoc.cat.common;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * Runs local executable files when a caller must execute generated or recently replaced content.
 * <p>
 * A concurrent process launch can inherit the writer's descriptor and make the executable briefly unavailable with
 * {@code ETXTBSY}. This boundary retries only that transient start failure with exponential backoff totaling at most
 * 875 milliseconds; it does not own file writing.
 *
 * @see <a href="https://bugs.openjdk.org/browse/JDK-8068370">JDK-8068370</a>.
 */
public final class ExecutableFiles
{
	private static final int MAXIMUM_START_ATTEMPTS = 4;
	private static final Duration INITIAL_TEXT_FILE_BUSY_RETRY_DELAY = Duration.ofMillis(125);

	/**
	 * Runs a configured executable process, retrying only a transient text-file-busy failure.
	 *
	 * @param builder the configured process builder
	 * @return the started process
	 * @throws NullPointerException if {@code builder} is {@code null}
	 * @throws IOException if the process cannot be started
	 */
	public static Process run(ProcessBuilder builder) throws IOException
	{
		Objects.requireNonNull(builder, "builder must not be null");
		return run(builder::start, Thread::sleep);
	}

	/**
	 * Runs an executable process through one controlled start operation.
	 *
	 * @param start the process-start operation
	 * @param wait the operation that waits for the requested retry delay
	 * @return the started process
	 * @throws NullPointerException if {@code start} is {@code null} or {@code wait} is {@code null}
	 * @throws IOException if the process cannot be started
	 */
	static Process run(ProcessStart start, RetryWait wait) throws IOException
	{
		Objects.requireNonNull(start, "start must not be null");
		Objects.requireNonNull(wait, "wait must not be null");
		int attempt = 1;
		while (true)
		{
			try
			{
				return start.start();
			}
			catch (IOException failure)
			{
				if (!isTextFileBusy(failure) || attempt == MAXIMUM_START_ATTEMPTS)
					throw failure;
				pauseBeforeRetry(attempt, wait);
				++attempt;
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
	 * Waits with exponential backoff before retrying an executable start.
	 *
	 * @param attempt the failed start attempt that precedes this retry
	 * @param wait the operation that waits for the requested retry delay
	 *
	 * @throws IOException if the retry wait is interrupted
	 */
	private static void pauseBeforeRetry(int attempt, RetryWait wait) throws IOException
	{
		Duration delay = INITIAL_TEXT_FILE_BUSY_RETRY_DELAY.multipliedBy(1L << (attempt - 1));

		// WORKAROUND: https://bugs.openjdk.org/browse/JDK-8068370
		try
		{
			wait.pause(delay);
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while retrying a text-file-busy process start", interrupted);
		}
	}

	/**
	 * Starts one process for a retry-controlled executable boundary.
	 */
	@FunctionalInterface
	interface ProcessStart
	{
		/**
		 * Starts the process.
		 *
		 * @return the started process
		 * @throws IOException if the process cannot be started
		 */
		Process start() throws IOException;
	}

	/**
	 * Waits for the delay requested by {@link ExecutableFiles}' retry policy.
	 */
	@FunctionalInterface
	interface RetryWait
	{
		/**
		 * Waits before the next process-start attempt.
		 *
		 * @param delay the requested delay
		 * @throws InterruptedException if the wait is interrupted
		 */
		void pause(Duration delay) throws InterruptedException;
	}

	/**
	 * Prevents construction of this static utility.
	 */
	private ExecutableFiles()
	{
	}
}
