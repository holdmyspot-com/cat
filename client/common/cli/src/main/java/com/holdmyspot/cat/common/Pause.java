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
	 * Only an application entry point should create this pause. Unit tests supply their own.
	 *
	 * @param duration how long each call blocks
	 * @return the pause
	 * @throws IllegalArgumentException if {@code duration} is not positive
	 */
	static Pause sleeping(Duration duration)
	{
		requireThat(duration, "duration").isNotNull();
		requireThat(duration.isPositive(), "duration.isPositive").isTrue();
		return () ->
		{
			try
			{
				Thread.sleep(duration);
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
}
