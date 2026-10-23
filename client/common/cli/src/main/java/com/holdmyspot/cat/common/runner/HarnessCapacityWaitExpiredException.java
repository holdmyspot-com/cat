package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.io.Serial;

/**
 * Thrown when a harness session waited for capacity as long as it was allowed and no permit became free.
 */
public final class HarnessCapacityWaitExpiredException extends IOException
{
	/**
	 * The exit status of a runner command that gave up waiting for capacity.
	 */
	public static final int EXIT_STATUS = 75;
	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param message the explanation
	 */
	public HarnessCapacityWaitExpiredException(String message)
	{
		super(message);
	}
}
