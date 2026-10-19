package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;

import java.time.Instant;
import java.util.List;

/**
 * The response to the rate limits that the harness reports.
 * <p>
 * Utilization is the fraction of a reported rate-limit allowance consumed. At {@value #FREEZE_UTILIZATION} or more
 * of any allowance, the limit on simultaneous candidate trials stops growing, even when an allowance is exhausted: the
 * harness may draw on several accounts, so only a rejection shows that it cannot start another session. A rate-limit
 * rejection or a provider refusal drops the limit to 1, and no trials start until the harness's shared hold ends; the
 * shared hold, not this throttle, keeps the sequence of hold lengths. The response adds no randomized pacing.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param holdUntil the earliest instant at which admissions may start, or {@link Instant#EPOCH} if none is held
 * @param windowCap the largest permitted concurrency window, or 0 if none is imposed
 * @param backoffs the shared hold's number of doubled holds when it last held admissions, or 0 after a trial completed
 */
public record Throttle(Instant holdUntil, int windowCap, int backoffs)
{
	/**
	 * The utilization at which the window stops growing.
	 */
	public static final double FREEZE_UTILIZATION = 0.80;
	/**
	 * No throttling.
	 */
	public static final Throttle NONE = new Throttle(Instant.EPOCH, 0, 0);

	/**
	 * Creates a throttle state.
	 *
	 * @param holdUntil the earliest instant at which admissions may start, or {@link Instant#EPOCH} if none is held
	 * @param windowCap the largest permitted concurrency window, or 0 if none is imposed
	 * @param backoffs the shared hold's number of doubled holds when it last held admissions, or 0 after a trial
	 *   completed
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
	 * @param window the active candidate's current concurrency window
	 * @return the updated state
	 * @throws NullPointerException if {@code windows} is null or contains null
	 * @throws IllegalArgumentException if {@code window} is not positive
	 */
	public Throttle observe(List<RateLimitWindow> windows, int window)
	{
		requireThat(windows, "windows").isNotNull();
		requireThat(window, "window").isPositive();
		if (windows.isEmpty())
			return this;
		double utilization = 0;
		for (RateLimitWindow reported : windows)
			utilization = Math.max(utilization, reported.utilization());
		if (utilization >= FREEZE_UTILIZATION)
		{
			if (windowCap > 0)
				return this;
			return new Throttle(holdUntil, window, backoffs);
		}
		return new Throttle(holdUntil, 0, backoffs);
	}

	/**
	 * Returns the state after the harness's shared hold stopped admissions.
	 *
	 * @param until when the shared hold ends
	 * @param backoffs the shared hold's number of doubled holds
	 * @return the updated state, which holds admissions until the later of {@code until} and the current hold's end
	 * @throws NullPointerException if {@code until} is null
	 * @throws IllegalArgumentException if {@code backoffs} is negative
	 */
	public Throttle hold(Instant until, int backoffs)
	{
		requireThat(until, "until").isNotNull();
		requireThat(backoffs, "backoffs").isNotNegative();
		Instant end = holdUntil;
		if (until.isAfter(end))
			end = until;
		return new Throttle(end, 1, backoffs);
	}

	/**
	 * Returns the state after a trial completed, which the shared hold also records as resetting its hold lengths.
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
	 * Indicates whether this throttle permits admission at the supplied time.
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
