package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Thrown when a prompt test's {@code scenario.md}, workspace files or values file does not follow the prompt test
 * format. The message names the location of the problem.
 */
public final class ScenarioFormatException extends Exception
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message the problem, starting with its location
	 */
	public ScenarioFormatException(String message)
	{
		super(message);
	}
}
