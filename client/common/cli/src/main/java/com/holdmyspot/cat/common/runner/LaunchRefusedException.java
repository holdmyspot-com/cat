package com.holdmyspot.cat.common.runner;

import java.io.IOException;

/**
 * Signals that no session started because no request server serves the host session, or the server or the child's
 * runner refused the session. The same launch is refused again until the user acts on the message, for example by
 * starting the request server or fixing the setting that it names, so a caller does not retry it.
 */
public final class LaunchRefusedException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a refusal.
	 *
	 * @param message why no session started and how the user fixes it
	 */
	public LaunchRefusedException(String message)
	{
		super(message);
	}
}
