package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;

/**
 * Exposes the system's process lookup with a chosen process folder, which tests of {@link HarnessProcesses#system()}
 * require.
 */
public final class HarnessProcessesSecrets
{
	private static HarnessProcessesAccess harnessProcessesAccess;

	/**
	 * Returns the access point of the system's process lookup.
	 *
	 * @return the access point
	 * @throws AssertionError if the system's process lookup does not register its access point
	 */
	public static HarnessProcessesAccess getHarnessProcessesAccess()
	{
		HarnessProcessesAccess access = harnessProcessesAccess;
		if (access == null)
		{
			// Creating the system's lookup initializes its class, which registers the access point
			HarnessProcesses.system();
			access = harnessProcessesAccess;
			if (access == null)
				throw new AssertionError("The system's process lookup did not register HarnessProcessesAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by the system's process lookup.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setHarnessProcessesAccess(HarnessProcessesAccess access)
	{
		harnessProcessesAccess = access;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessProcessesSecrets()
	{
	}
}
