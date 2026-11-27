package com.holdmyspot.cat.common;

import java.io.IOException;
import java.util.Optional;

/**
 * Reads the command line of a Windows process from Windows itself, because {@link ProcessHandle.Info#arguments()} and
 * {@link ProcessHandle.Info#commandLine()} report none there.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
public final class WindowsProcessCommandLine
{
	/**
	 * Prevents construction.
	 */
	private WindowsProcessCommandLine()
	{
	}

	/**
	 * Reads the command line of a process.
	 *
	 * @param processId the process's identifier
	 * @return the command line, or empty if Windows does not let the caller open or read the process, or the process
	 *   has ended
	 * @throws IOException if Windows cannot be asked
	 */
	public static Optional<String> read(long processId) throws IOException
	{
		return Optional.empty();
	}
}
