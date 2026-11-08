package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;

/**
 * Thrown when a request file cannot be opened exclusively because another handle to it is open, such as the
 * handle of a program that scans or indexes a file that was just written. The file may be unsafe or may only be
 * busy, so a receiver that can look again later does so, and a receiver that cannot refuses the file.
 */
public final class RequestArchiveBusyException extends IOException
{
	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception.
	 *
	 * @param file the request file that another handle holds open
	 */
	public RequestArchiveBusyException(Path file)
	{
		super("Cannot open request archive exclusively, because another handle to it is open: " + file);
	}
}
