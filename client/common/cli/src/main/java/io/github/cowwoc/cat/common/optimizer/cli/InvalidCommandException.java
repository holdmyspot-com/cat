package io.github.cowwoc.cat.common.optimizer.cli;

/**
 * Thrown when the arguments of a command are malformed. The command has not reached the core.
 */
public final class InvalidCommandException extends IllegalArgumentException
{
	@java.io.Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param message what is wrong with the arguments and what the correct form is
	 */
	public InvalidCommandException(String message)
	{
		super(message);
	}
}
