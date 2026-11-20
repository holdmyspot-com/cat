package com.holdmyspot.cat.common.runner;

import java.io.IOException;

/** The broker started a guarded continuation whose harness could not resume the retained session. */
public final class RetainedTrialUnresumableException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the failure.
	 *
	 * @param message the broker's diagnostic for the failed continuation
	 */
	public RetainedTrialUnresumableException(String message)
	{
		super(message);
	}
}
