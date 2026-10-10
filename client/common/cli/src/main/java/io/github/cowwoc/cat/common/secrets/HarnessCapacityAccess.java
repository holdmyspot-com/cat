package io.github.cowwoc.cat.common.secrets;

import io.github.cowwoc.cat.common.LockFileOpener;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.Pause;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Exposes how a capacity pool opens its lock files to the module's tests.
 */
@FunctionalInterface
public interface HarnessCapacityAccess
{
	/**
	 * Creates a capacity pool whose slots are files in a directory, opening its lock files through a given
	 * opener.
	 *
	 * @param lockDirectory the directory that holds the slot files. It is created on first use.
	 * @param maximum the number of permits that may be held at once, from 1 to
	 *   {@link HarnessCapacity#MAXIMUM_CONCURRENCY}
	 * @param pause the wait that {@link HarnessCapacity#acquire()} performs between attempts
	 * @param clock the clock that decides whether a hold lasts
	 * @param opener opens the slot and hold lock files, creating them if needed
	 * @return the pool
	 * @throws NullPointerException if {@code lockDirectory}, {@code pause}, {@code clock} or {@code opener} are
	 *   null
	 * @throws IllegalArgumentException if {@code maximum} is out of range
	 */
	HarnessCapacity of(Path lockDirectory, int maximum, Pause pause, Clock clock, LockFileOpener opener);
}
