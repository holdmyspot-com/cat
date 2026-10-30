package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.time.Duration;

/**
 * Waits for a short while before the caller retries an operation.
 * <p>
 * Callers receive a pause instead of sleeping themselves, so that tests control the passage of time.
 */
@FunctionalInterface
public interface Pause
{
	/**
	 * Returns a pause that blocks the calling thread for a fixed duration.
	 * <p>
	 * Only an application entry point should create this pause, usually as {@code Pause::sleeping} for a
	 * {@link Waiting}. Unit tests supply their own.
	 *
	 * @param duration how long each call blocks
	 * @return the pause
	 * @throws NullPointerException if {@code duration} is null
	 * @throws IllegalArgumentException if {@code duration} is not positive
	 */
	static Pause sleeping(Duration duration)
	{
		requireThat(duration, "duration").isNotNull();
		requireThat(duration.isPositive(), "duration.isPositive").isTrue();
		return interruptible(() -> Thread.sleep(duration));
	}

	/**
	 * Returns a pause that performs a blocking wait and reports an interrupted wait as an {@code IOException}, keeping
	 * the thread's interrupt status set.
	 *
	 * @param wait the blocking wait
	 * @return the pause
	 * @throws NullPointerException if {@code wait} is null
	 */
	static Pause interruptible(BlockingWait wait)
	{
		requireThat(wait, "wait").isNotNull();
		return () ->
		{
			try
			{
				wait.await();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting to retry", e);
			}
		};
	}

	/**
	 * Waits before the caller's next attempt.
	 *
	 * @throws IOException if the wait is cut short, for example because the thread was interrupted
	 */
	void pause() throws IOException;

	/**
	 * A wait that blocks the calling thread until it ends or the thread is interrupted.
	 */
	@FunctionalInterface
	interface BlockingWait
	{
		/**
		 * Blocks until the wait ends.
		 *
		 * @throws InterruptedException if the thread is interrupted while it waits
		 */
		void await() throws InterruptedException;
	}
}
