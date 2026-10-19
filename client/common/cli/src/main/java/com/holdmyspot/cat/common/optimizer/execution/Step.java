package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Instant;

/**
 * What follows one {@link Driver#step()}.
 */
public sealed interface Step
{
	/**
	 * The slice continues: the driver waits for a session to end, or until {@code wakeBy}, and steps again.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param wakeBy the latest time at which the driver must step again even if no session ends
	 */
	record Continue(Instant wakeBy) implements Step
	{
		/**
		 * Creates the step.
		 *
		 * @param wakeBy the latest time at which the driver must step again even if no session ends
		 * @throws NullPointerException if {@code wakeBy} is null
		 */
		public Continue
		{
			requireThat(wakeBy, "wakeBy").isNotNull();
		}
	}

	/**
	 * The slice ended: nothing runs and the state is persisted.
	 * <p>
	 * <b>Thread Safety:</b> This class is immutable and thread-safe.
	 *
	 * @param result what the slice returns to the host agent
	 */
	record Stopped(SliceResult result) implements Step
	{
		/**
		 * Creates the step.
		 *
		 * @param result what the slice returns to the host agent
		 * @throws NullPointerException if {@code result} is null
		 */
		public Stopped
		{
			requireThat(result, "result").isNotNull();
		}
	}
}
