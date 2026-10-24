package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Pause;

import java.io.IOException;
import java.time.Clock;

/**
 * Exposes how executable files start a process to the module's tests.
 */
public interface ExecutableFilesAccess
{
	/**
	 * Runs an executable process through one controlled start operation.
	 *
	 * @param executables the instance whose retry policy applies
	 * @param start       the process-start operation
	 * @return the started process
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the process cannot be started, or the pause between attempts fails
	 */
	Process run(ExecutableFiles executables, ExecutableFiles.ProcessStart start) throws IOException;

	/**
	 * Creates an instance whose process starts can be controlled by a test.
	 *
	 * @param pause    waits between attempts
	 * @param clock    measures how long a start has been trying
	 * @param launcher starts the configured process on each attempt
	 * @return the instance
	 * @throws NullPointerException if any of the arguments are null
	 */
	ExecutableFiles create(Pause pause, Clock clock, ExecutableFiles.ProcessLauncher launcher);
}
