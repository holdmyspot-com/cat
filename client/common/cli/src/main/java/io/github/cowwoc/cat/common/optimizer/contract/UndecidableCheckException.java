package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Thrown when a check's kind needs evidence that the trial's evidence does not record, such as tool calls from a
 * harness whose adapter does not report them. The caller treats this as an infrastructure failure, never as a pass
 * or a fail.
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
