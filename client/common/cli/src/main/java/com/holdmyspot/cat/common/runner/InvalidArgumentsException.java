package com.holdmyspot.cat.common.runner;

/**
 * Thrown when the options of a runner launcher's launch are invalid. A runner launcher reports its message as the
 * reason for refusing the launch.
 */
public final class InvalidArgumentsException extends IllegalArgumentException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception.
	 *
	 * @param message the reason that the options are invalid
	 */
	public InvalidArgumentsException(String message)
	{
		super(message);
	}
}
