package com.holdmyspot.cat.common.runner.launcher;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.ProcessDescription;

/**
 * One operating-system process, told apart from a later process that reuses its identifier by its start, as
 * {@link ProcessDescription#start()} describes it.
 *
 * @param pid   the process identifier
 * @param start the process's start, which stays the same for as long as the process lives and which a later process
 *              with the same identifier does not have
 */
public record ProcessIdentity(long pid, String start)
{
	/**
	 * Creates the identity of a process.
	 *
	 * @param pid   the process identifier
	 * @param start the process's start: lower-case letters and digits, so that it can be part of a launcher's name
	 * @throws NullPointerException     if {@code start} is null
	 * @throws IllegalArgumentException if {@code pid} is not positive, or {@code start} has another form
	 */
	public ProcessIdentity
	{
		requireThat(pid, "pid").isPositive();
		requireThat(start, "start").matches(ProcessDescription.START_PATTERN);
	}
}
