package io.github.cowwoc.cat.common.runner;

import java.nio.file.Path;

/**
 * Resolves runner-owned temporary workflow state beneath the CAT root supplied by the owning tool scope.
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
	 * @param catTemp the CAT-owned temporary root from the owning tool scope
	 * @return the absolute runner-owned temporary root
	 */
	public static Path defaultRunRoot(Path catTemp)
	{
		return catTemp.toAbsolutePath().normalize().resolve("runner-runs");
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
