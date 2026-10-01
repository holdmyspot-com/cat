package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The response to the rate limits that the harness reports.
 * <p>
 * At {@value #FREEZE_UTILIZATION} utilization or more of any window, the concurrency window stops growing. At
 * {@value #HOLD_UTILIZATION} or more, or on a rate-limit rejection, the window drops to 1 and nothing is admitted
 * until the reported reset time. Without a reset time, admissions back off for 1, 2, 4, … minutes, up to 30 minutes.
 * The response is deterministic: it adds no randomized pacing.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param holdUntil the earliest instant at which admissions may start, or {@link Instant#EPOCH} if none is held
 * @param windowCap the largest permitted concurrency window, or 0 if none is imposed
 * @param backoffs the consecutive backoffs without a reset time since the last completed trial
 */
public record Throttle(Instant holdUntil, int windowCap, int backoffs)
{
	/**
	 * The utilization at which the window stops growing.
	 */
	public static final double FREEZE_UTILIZATION = 0.80;
	/**
	 * The utilization at which admissions stop until the reset time.
	 */
	public static final double HOLD_UTILIZATION = 0.95;
	/**
	 * The longest backoff.
	 */
	public static final Duration MAXIMUM_BACKOFF = Duration.ofMinutes(30);
	/**
	 * The number of doublings after which the backoff exceeds {@link #MAXIMUM_BACKOFF}.
	 */
	private static final int BACKOFF_DOUBLINGS = 5;
	/**
	 * No throttling.
	 */
	public static final Throttle NONE = new Throttle(Instant.EPOCH, 0, 0);

	/**
	 * Creates a throttle state.
	 *
	 * @param holdUntil the earliest instant at which admissions may start, or {@link Instant#EPOCH} if none is held
	 * @param windowCap the largest permitted concurrency window, or 0 if none is imposed
	 * @param backoffs the consecutive backoffs without a reset time since the last completed trial
	 * @throws NullPointerException if {@code holdUntil} is null
	 * @throws IllegalArgumentException if {@code windowCap} or {@code backoffs} are negative
	 */
	public Throttle
	{
		requireThat(holdUntil, "holdUntil").isNotNull();
		requireThat(windowCap, "windowCap").isNotNegative();
		requireThat(backoffs, "backoffs").isNotNegative();
	}

	/**
	 * Returns the state after the harness reported its rate-limit windows.
	 *
	 * @param windows the reported windows; an empty list leaves the state unchanged
	 * @param now the current time
	 * @param window the active candidate's current concurrency window
	 * @return the updated state
	 * @throws NullPointerException if {@code windows} or {@code now} are null
	 * @throws IllegalArgumentException if {@code window} is not positive
	 */
	public Throttle observe(List<RateLimitWindow> windows, Instant now, int window)
	{
		requireThat(windows, "windows").isNotNull();
		requireThat(now, "now").isNotNull();
		requireThat(window, "window").isPositive();
		if (windows.isEmpty())
			return this;
		double utilization = 0;
		for (RateLimitWindow reported : windows)
			utilization = Math.max(utilization, reported.utilization());
		if (utilization >= HOLD_UTILIZATION)
		{
			Instant latestReset = null;
			boolean unknownReset = false;
			for (RateLimitWindow reported : windows)
			{
				if (reported.utilization() < HOLD_UTILIZATION)
					continue;
				Instant resetsAt = reported.resetsAt();
				if (resetsAt == null)
					unknownReset = true;
				else if (latestReset == null || resetsAt.isAfter(latestReset))
					latestReset = resetsAt;
			}
			if (unknownReset || !latestReset.isAfter(now))
				return backOff(now);
			return new Throttle(latestReset, 1, backoffs);
		}
		if (utilization >= FREEZE_UTILIZATION)
		{
			if (windowCap > 0)
				return this;
			return new Throttle(holdUntil, window, backoffs);
		}
		return new Throttle(holdUntil, 0, backoffs);
	}

	/**
	 * Returns the state after the provider rejected a trial because of a rate limit.
	 *
	 * @param now the current time
	 * @param resetsAt when the provider accepts requests again, or {@code null} if it did not say
	 * @return the updated state
	 * @throws NullPointerException if {@code now} is null
	 */
	public Throttle reject(Instant now, Instant resetsAt)
	{
		requireThat(now, "now").isNotNull();
		if (resetsAt == null || !resetsAt.isAfter(now))
			return backOff(now);
		return new Throttle(resetsAt, 1, backoffs);
	}

	/**
	 * Returns the state after a backoff without a usable reset time.
	 *
	 * @param now the current time
	 * @return the updated state
	 */
	private Throttle backOff(Instant now)
	{
		long minutes = Math.min(1L << Math.min(backoffs, BACKOFF_DOUBLINGS), MAXIMUM_BACKOFF.toMinutes());
		return new Throttle(now.plus(Duration.ofMinutes(minutes)), 1, backoffs + 1);
	}

	/**
	 * Returns the state after a trial completed, which resets the backoff sequence.
	 *
	 * @return the updated state
	 */
	public Throttle afterCompletion()
	{
		if (backoffs == 0)
			return this;
		return new Throttle(holdUntil, windowCap, 0);
	}

	/**
	 * Indicates whether admissions may start.
	 *
	 * @param now the current time
	 * @return {@code true} if no hold lasts beyond {@code now}
	 * @throws NullPointerException if {@code now} is null
	 */
	public boolean allowsAdmission(Instant now)
	{
		requireThat(now, "now").isNotNull();
		return !now.isBefore(holdUntil);
	}

	/**
	 * Applies the window cap to a concurrency window.
	 *
	 * @param window the window that the other limits permit
	 * @return the permitted window
	 * @throws IllegalArgumentException if {@code window} is not positive
	 */
	public int limit(int window)
	{
		requireThat(window, "window").isPositive();
		if (windowCap == 0)
			return window;
		return Math.min(window, windowCap);
	}
}
