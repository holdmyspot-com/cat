package io.github.cowwoc.cat.common.optimizer.store;

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
