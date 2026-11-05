package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One operating-system process, told apart from a later process that reuses its identifier by the instant at which it
 * started.
 *
 * @param pid   the process identifier
 * @param start the instant at which the process started, to the millisecond
 */
public record ProcessIdentity(long pid, Instant start)
{
	/**
	 * Creates the identity of a process.
	 *
	 * @param pid   the process identifier
	 * @param start the instant at which the process started; it is truncated to the millisecond, the precision with
	 *              which a launcher's directory name records it
	 * @throws NullPointerException     if {@code start} is null
	 * @throws IllegalArgumentException if {@code pid} is not positive
	 */
	public ProcessIdentity
	{
		requireThat(pid, "pid").isPositive();
		requireThat(start, "start").isNotNull();
		start = start.truncatedTo(ChronoUnit.MILLIS);
	}
}
