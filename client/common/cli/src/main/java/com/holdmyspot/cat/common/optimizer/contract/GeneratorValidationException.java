package com.holdmyspot.cat.common.optimizer.contract;

/**
 * Thrown when a generated trial is outside what its prompt test permits. The trial cannot supply a pass or
 * fail outcome; the caller records a failure to run the trial rather than a failed trial.
 */
public final class GeneratorValidationException extends RuntimeException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message the prompt-test constraint that the generated trial violates
	 */
	public GeneratorValidationException(String message)
	{
		super(message);
	}
}
