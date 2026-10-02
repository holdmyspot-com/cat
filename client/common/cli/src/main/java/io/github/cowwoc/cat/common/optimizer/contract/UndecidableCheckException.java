package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Thrown when a check needs evidence that was not recorded, such as tool calls from a harness whose adapter does not
 * report them. The caller records a failure to measure compliance rather than a compliance pass or failure.
 */
public final class UndecidableCheckException extends Exception
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message what the evidence lacks
	 */
	public UndecidableCheckException(String message)
	{
		super(message);
	}
}
