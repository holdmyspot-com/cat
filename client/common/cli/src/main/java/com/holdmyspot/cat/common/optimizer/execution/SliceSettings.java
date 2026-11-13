package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;
import java.time.Instant;

/**
 * The time limits of one slice: the bounded interval during which {@link SliceRunner} advances the {@link Driver}.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param budget how long after the slice starts new work may be admitted
 * @param grace how long after the budget running sessions may finish before they are cancelled
 * @param background whether the run has no time cutoff
 * @param pollInterval the longest time that {@link SliceRunner} waits before it checks for a host interrupt again
 */
public record SliceSettings(Duration budget, Duration grace, Duration pollInterval, boolean background)
{
	/**
	 * Creates foreground settings.
	 *
	 * @param budget the admission budget
	 * @param grace the completion grace period
	 * @param pollInterval the interrupt polling interval
	 */
	public SliceSettings(Duration budget, Duration grace, Duration pollInterval)
	{
		this(budget, grace, pollInterval, false);
	}

	/**
	 * Returns settings with no time cutoff; grace applies only to an explicit cancellation.
	 *
	 * @return the background settings
	 */
	public SliceSettings withoutDeadline()
	{
		return new SliceSettings(budget, grace, pollInterval, true);
	}

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
	 * @param background whether the run has no time cutoff
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

	/**
	 * Returns the end of a slice's budget: the time after which no new work is admitted.
	 *
	 * @param sliceStart when the slice started
	 * @return the deadline
	 * @throws NullPointerException if {@code sliceStart} is null
	 */
	public Instant deadline(Instant sliceStart)
	{
		requireThat(sliceStart, "sliceStart").isNotNull();
		if (background)
			return Instant.MAX;
		return sliceStart.plus(budget);
	}

	/**
	 * Returns the end of a slice's grace period: the time at which the sessions that still run are cancelled.
	 *
	 * @param sliceStart when the slice started
	 * @return the end of the grace period
	 * @throws NullPointerException if {@code sliceStart} is null
	 */
	public Instant graceEnd(Instant sliceStart)
	{
		if (background)
			return Instant.MAX;
		return deadline(sliceStart).plus(grace);
	}
}
