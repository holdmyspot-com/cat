package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.HarnessRunManager;

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
	 * @param fileLocks the process-owned lock coordinator
	 * @param root the absolute owned-run root
	 * @param clock the clock used for generated identifiers
	 * @param replacer replaces the runs' manifests and output files
	 * @param processLiveness the exact-process liveness service
	 * @return the manager
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code root} is relative
	 */
	HarnessRunManager newHarnessRunManager(FileLocks fileLocks, Path root, Clock clock, FileReplacer replacer,
		HarnessProcessLiveness processLiveness);
}
