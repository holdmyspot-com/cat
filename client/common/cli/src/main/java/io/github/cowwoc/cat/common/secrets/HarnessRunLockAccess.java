package io.github.cowwoc.cat.common.secrets;

import io.github.cowwoc.cat.common.LockFileOpener;
import io.github.cowwoc.cat.common.runner.HarnessRunLock;

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
	 * @param file the regular run lock file
	 * @param opener opens the run lock file
	 * @return the acquired lock
	 * @throws IOException if the file cannot be locked or is already locked
	 */
	HarnessRunLock acquire(Path file, LockFileOpener opener) throws IOException;
}
