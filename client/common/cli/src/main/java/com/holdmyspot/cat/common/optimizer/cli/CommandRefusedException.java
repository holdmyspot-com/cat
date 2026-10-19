package com.holdmyspot.cat.common.optimizer.cli;

/**
 * Thrown when a command is well-formed but the campaign's state or binding does not permit it. A command that throws
 * this exception has changed nothing.
 */
public final class CommandRefusedException extends Exception
{
	@java.io.Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param message why the command is refused and what the user can do instead
	 */
	public CommandRefusedException(String message)
	{
		super(message);
	}

	/**
	 * Creates the exception.
	 *
	 * @param message why the command is refused and what the user can do instead
	 * @param cause the failure that makes the command impossible
	 */
	public CommandRefusedException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
