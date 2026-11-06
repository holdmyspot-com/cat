package com.holdmyspot.cat.common.installer.engine.internal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides how long an installer operation pauses before it tries the harness lock again.
 */
public final class LockPause
{
	/**
	 * The longest pause between lock attempts; the time that remains limits the final pause further.
	 */
	private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

	/**
	 * Prevents instantiation.
	 */
	private LockPause()
	{
	}

	/**
	 * Returns the pause before the next lock attempt. The wait ends at the earlier of the deadline on the clock and the
	 * end of the wait measured by elapsed time, so a clock that jumps forward ends the wait early and a clock that
	 * stalls or jumps back does not extend it.
	 *
	 * @param now     the current instant on the clock
	 * @param deadline the instant on the clock at which the wait ends
	 * @param wait    the maximum wait
	 * @param elapsed the time that has passed since the wait began, measured independently of the clock
	 * @return the pause, or empty when the wait is over
	 */
	public static Optional<Duration> next(Instant now, Instant deadline, Duration wait, Duration elapsed)
	{
		requireThat(now, "now").isNotNull();
		requireThat(deadline, "deadline").isNotNull();
		requireThat(wait, "wait").isNotNull();
		requireThat(elapsed, "elapsed").isNotNull();
		Duration remaining = wait.minus(elapsed);
		Duration clockRemaining = Duration.between(now, deadline);
		if (clockRemaining.compareTo(remaining) < 0)
			remaining = clockRemaining;
		if (remaining.isNegative() || remaining.isZero())
			return Optional.empty();
		if (remaining.compareTo(POLL_INTERVAL) > 0)
			remaining = POLL_INTERVAL;
		return Optional.of(remaining);
	}
}
