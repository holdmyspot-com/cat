package com.holdmyspot.cat.common.updater.secrets;

import com.holdmyspot.cat.common.updater.engine.ProcessElevation;
import java.lang.invoke.MethodHandles;

/**
 * Supplies typed access to {@link ProcessElevation}'s checks for its test module.
 */
public final class ProcessElevationSecrets
{
	private static ProcessElevationAccess processElevationAccess;

	/**
	 * Prevents construction of this static facade.
	 */
	private ProcessElevationSecrets()
	{
	}

	/**
	 * Registers access from the owning class's initializer.
	 *
	 * @param access the checks
	 */
	public static void setProcessElevationAccess(ProcessElevationAccess access)
	{
		processElevationAccess = access;
	}

	/**
	 * Returns the registered access, initializing its owner when needed.
	 *
	 * @return the checks
	 */
	public static ProcessElevationAccess getProcessElevationAccess()
	{
		ProcessElevationAccess access = processElevationAccess;
		if (access == null)
		{
			try
			{
				MethodHandles.lookup().ensureInitialized(ProcessElevation.class);
			}
			catch (IllegalAccessException failure)
			{
				throw new AssertionError("Cannot initialize " + ProcessElevation.class.getName(), failure);
			}
			access = processElevationAccess;
			if (access == null)
			{
				throw new AssertionError(ProcessElevation.class.getName() +
					" did not register ProcessElevationAccess");
			}
		}
		return access;
	}
}
