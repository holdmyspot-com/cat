package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.SessionIgnore;

/**
 * Exposes how {@link SessionIgnore} starts Git to the module's tests.
 */
@FunctionalInterface
public interface SessionIgnoreAccess
{
	/**
	 * Creates the questions that {@link SessionIgnore#GIT_PROCESS} asks, starting each Git process through a launcher
	 * instead of the operating system.
	 *
	 * @param launcher starts the configured Git process
	 * @return the questions
	 * @throws NullPointerException if {@code launcher} is null
	 */
	SessionIgnore.GitQueries gitProcess(ExecutableFiles.ProcessLauncher launcher);
}
