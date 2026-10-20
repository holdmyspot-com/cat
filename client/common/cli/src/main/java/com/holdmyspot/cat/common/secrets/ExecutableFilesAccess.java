package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.ExecutableFiles;

import java.io.IOException;

/**
 * Exposes how executable files start a process to the module's tests.
 */
@FunctionalInterface
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
}
