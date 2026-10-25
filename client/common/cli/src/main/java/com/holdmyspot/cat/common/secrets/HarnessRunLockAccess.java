package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.LockFileOpener;
import com.holdmyspot.cat.common.runner.HarnessRunLock;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Exposes how a run lock opens its file to the module's tests.
 */
@FunctionalInterface
public interface HarnessRunLockAccess
{
	/**
	 * Acquires an exclusive lock on an existing run lock file, opening it through a given opener.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param file the regular run lock file
	 * @param opener opens the run lock file
	 * @return the acquired lock
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the file cannot be locked or is already locked
	 */
	HarnessRunLock acquire(FileLocks fileLocks, Path file, LockFileOpener opener) throws IOException;
}
