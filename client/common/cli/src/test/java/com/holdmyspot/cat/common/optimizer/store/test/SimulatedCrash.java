package com.holdmyspot.cat.common.optimizer.store.test;

import com.holdmyspot.cat.common.optimizer.store.WriteStep;

/**
 * Simulates a process that stops after a write step.
 */
final class SimulatedCrash extends RuntimeException
{
	private static final long serialVersionUID = 0L;

	/**
	 * Creates the exception.
	 *
	 * @param step the last step that completed
	 */
	SimulatedCrash(WriteStep step)
	{
		super("Crashed after " + step);
	}
}
