package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Instant;

/**
 * One rate-limit window that a harness reported, such as a five-hour or weekly allowance.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param utilization the fraction of the window that is used, from 0 to 1
 * @param resetsAt when the window resets, or {@code null} if the harness did not report it
 */
public record RateLimitWindow(double utilization, Instant resetsAt)
{
	/**
	 * Creates a window observation.
	 *
	 * @param utilization the fraction of the window that is used, from 0 to 1
	 * @param resetsAt when the window resets, or {@code null} if the harness did not report it
	 * @throws IllegalArgumentException if {@code utilization} is outside [0, 1]
	 */
	public RateLimitWindow
	{
		requireThat(utilization, "utilization").isBetween(0.0, true, 1.0, true);
	}
}
