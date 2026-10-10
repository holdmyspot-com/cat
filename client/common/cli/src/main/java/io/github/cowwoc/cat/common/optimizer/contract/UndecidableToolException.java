package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Thrown when a tool needs evidence that was not recorded, such as tool calls from a harness whose adapter does not
 * report them. The caller records a failure to measure rather than a pass or a failure.
 */
public final class UndecidableToolException extends Exception
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message what the evidence lacks
	 */
	public UndecidableToolException(String message)
	{
		super(message);
	}
}
