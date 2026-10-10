package io.github.cowwoc.cat.common.secrets;

import io.github.cowwoc.cat.common.ExecutableFiles;

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
	 * @param start the process-start operation
	 * @param wait the operation that waits for the requested retry delay
	 * @return the started process
	 * @throws NullPointerException if {@code start} is {@code null} or {@code wait} is {@code null}
	 * @throws IOException if the process cannot be started
	 */
	Process run(ExecutableFiles.ProcessStart start, ExecutableFiles.RetryWait wait) throws IOException;
}
