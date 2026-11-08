package com.holdmyspot.cat.common.installer.cli.internal;

import java.io.IOException;

/**
 * Reports that a harness program is not on the search path that the installer searched. Running the same installer
 * command again in the same environment finds no program either, so only the user can change the outcome.
 */
final class ProgramNotFoundException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param message the sentence that reports the failure to the user
	 */
	ProgramNotFoundException(String message)
	{
		super(message);
	}
}
