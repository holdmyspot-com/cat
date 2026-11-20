package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.LockFileOpener;
import com.holdmyspot.cat.common.runner.NestedSessionLock;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Exposes how a nested-session lock opens its file to the module's tests.
 */
@FunctionalInterface
public interface NestedSessionLockAccess
{
	/**
	 * Acquires an exclusive lock on an existing nested-session lock file, opening it through a given opener.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param file the regular nested-session lock file
	 * @param opener opens the nested-session lock file
	 * @return the acquired lock
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if the file cannot be locked or is already locked
	 */
	NestedSessionLock acquire(FileLocks fileLocks, Path file, LockFileOpener opener) throws IOException;
}
