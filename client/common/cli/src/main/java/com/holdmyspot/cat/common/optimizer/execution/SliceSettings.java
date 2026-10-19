package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;

/**
 * The time limits of one slice: the bounded interval during which {@link SliceRunner} advances the {@link Driver}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param budget how long after the slice starts new work may be admitted
 * @param grace how long after the budget running sessions may finish before they are cancelled
 * @param pollInterval the longest time that {@link SliceRunner} waits before it checks for a host interrupt again
 */
public record SliceSettings(Duration budget, Duration grace, Duration pollInterval)
{
	/**
	 * The default settings: an 8-minute budget and a 1-minute grace period, checking for a
	 * host interrupt every second.
	 */
	public static final SliceSettings DEFAULT = new SliceSettings(Duration.ofMinutes(8), Duration.ofMinutes(1),
		Duration.ofSeconds(1));

	/**
	 * Creates the settings.
	 *
	 * @param budget how long after the slice starts new work may be admitted
	 * @param grace how long after the budget running sessions may finish before they are cancelled
	 * @param pollInterval the longest time that {@link SliceRunner} waits before it checks for a host interrupt
	 *   again
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code budget} or {@code pollInterval} are not positive, or {@code grace} is
	 *   negative
	 */
	public SliceSettings
	{
		requireThat(budget, "budget").isNotNull();
		requireThat(grace, "grace").isNotNull();
		requireThat(pollInterval, "pollInterval").isNotNull();
		requireThat(budget.isPositive(), "budget.isPositive()").isTrue();
		requireThat(grace.isNegative(), "grace.isNegative()").isFalse();
		requireThat(pollInterval.isPositive(), "pollInterval.isPositive()").isTrue();
	}
}
