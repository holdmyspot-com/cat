package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Thrown when a generated trial is outside what its test definition permits. The trial cannot supply compliance
 * evidence; the caller records a failure to run the trial rather than a failure to comply.
 */
public final class GeneratorValidationException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message the test-definition constraint that the generated trial violates
	 */
	public GeneratorValidationException(String message)
	{
		super(message);
	}
}
