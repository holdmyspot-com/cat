package io.github.cowwoc.cat.common.runner;

import java.nio.file.Path;

/**
 * Resolves runner-owned temporary workflow state below the CAT work temp directory from the owning tool scope.
 */
public final class HarnessTemporaryRoot
{
	/**
	 * Prevents instantiation.
	 */
	private HarnessTemporaryRoot()
	{
		// Utility class.
	}

	/**
	 * Returns the default root for isolated harness runs.
	 *
	 * @param temporaryRoot the CAT work temp directory from the owning tool scope
	 * @return the absolute directory that will contain isolated runner runs
	 */
	public static Path defaultRunRoot(Path temporaryRoot)
	{
		return temporaryRoot.toAbsolutePath().normalize().resolve("runner-runs");
	}

	/**
	 * Resolves an explicitly selected run root.
	 *
	 * @param runRoot the requested run root
	 * @return the normalized requested run root
	 */
	static Path explicitRunRoot(Path runRoot)
	{
		return runRoot.toAbsolutePath().normalize();
	}
}
