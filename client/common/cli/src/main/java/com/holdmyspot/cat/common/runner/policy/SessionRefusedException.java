package com.holdmyspot.cat.common.runner.policy;

import java.io.IOException;

/**
 * Signals that the runner refuses to start a session because the session's sandbox would be wider than its policy or
 * could not confine it. The session's commands never run.
 */
public class SessionRefusedException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a refusal.
	 *
	 * @param message the reason and how to fix it
	 */
	public SessionRefusedException(String message)
	{
		super(message);
	}

	/**
	 * Creates a refusal.
	 *
	 * @param message the reason and how to fix it
	 * @param cause   the failure that shows the reason
	 */
	public SessionRefusedException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
