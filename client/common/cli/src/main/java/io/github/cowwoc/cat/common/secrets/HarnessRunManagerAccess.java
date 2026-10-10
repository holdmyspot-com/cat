package io.github.cowwoc.cat.common.secrets;

import io.github.cowwoc.cat.common.runner.HarnessProcessLiveness;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Exposes the creation of run managers and the manifest limit to the module's tests.
 */
public interface HarnessRunManagerAccess
{
	/**
	 * Returns the largest run manifest, in bytes, that a manager reads.
	 *
	 * @return the number of bytes
	 */
	int maximumManifestBytes();

	/**
	 * Creates a manager with process liveness support.
	 *
	 * @param root the absolute owned-run root
	 * @param clock the clock used for generated identifiers
	 * @param processLiveness the exact-process liveness service
	 * @return the manager
	 * @throws NullPointerException if {@code root}, {@code clock}, or {@code processLiveness} are null
	 * @throws IllegalArgumentException if {@code root} is relative
	 */
	HarnessRunManager newHarnessRunManager(Path root, Clock clock, HarnessProcessLiveness processLiveness);
}
