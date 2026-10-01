package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Thrown when a generated instance is outside what its test definition permits. The caller treats this as an
 * infrastructure failure, not as evidence.
 */
public final class GeneratorValidationException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message what the instance got wrong
	 */
	public GeneratorValidationException(String message)
	{
		super(message);
	}
}
