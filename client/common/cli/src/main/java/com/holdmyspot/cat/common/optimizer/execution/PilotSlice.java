package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;

import java.time.Clock;
import java.time.Instant;

/**
 * The time bound that a command puts on the guard pilot that it runs: the pilot admits no trial that is expected to end
 * after the slice's budget, cancels the sessions that still run when the grace period ends, and cancels them when the
 * host interrupts the command. A pilot that a slice leaves unfinished continues in the next command.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe if its clock and interrupt signal are.
 *
 * @param clock the clock that measures the slice
 * @param settings the slice's budget, grace period and poll interval, which the driver's slices use too
 * @param interrupts reports whether the host interrupted the command
 * @param start when the command started
 */
public record PilotSlice(Clock clock, SliceSettings settings, HostInterruptSignal interrupts, Instant start)
{
	/**
	 * Creates a slice.
	 *
	 * @param clock the clock that measures the slice
	 * @param settings the slice's budget, grace period and poll interval
	 * @param interrupts reports whether the host interrupted the command
	 * @param start when the command started
	 * @throws NullPointerException if any of the arguments are null
	 */
	public PilotSlice
	{
		requireThat(clock, "clock").isNotNull();
		requireThat(settings, "settings").isNotNull();
		requireThat(interrupts, "interrupts").isNotNull();
		requireThat(start, "start").isNotNull();
	}

	/**
	 * Returns the end of the slice's budget.
	 *
	 * @return the time after which the pilot admits no trial
	 */
	public Instant deadline()
	{
		return settings.deadline(start);
	}

	/**
	 * Returns the end of the slice's grace period.
	 *
	 * @return the time at which the pilot cancels the sessions that still run
	 */
	public Instant graceEnd()
	{
		return settings.graceEnd(start);
	}
}
