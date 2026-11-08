package com.holdmyspot.cat.common.installer.cli.internal;

import java.io.IOException;

/**
 * Reports that a harness program could not start in the environment that the installer runs it with: the process
 * could not be launched, or it exited with status 126 or 127, as a shell does when it cannot run a command or the
 * interpreter that the command names. The program may still be the harness's program, so the failure says nothing
 * about the program's identity, and running the same installer command again in the same environment fails the same
 * way.
 */
final class ProgramStartException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param message the sentences that report the failure to the user
	 * @param cause   the failure to launch the process, or null when the process ran and exited
	 */
	ProgramStartException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
