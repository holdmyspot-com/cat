package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.HarnessRunnerMaintenance;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link HarnessRunnerMaintenance} execution required by tests.
 */
public final class HarnessRunnerMaintenanceSecrets
{
	private static HarnessRunnerMaintenanceAccess harnessRunnerMaintenanceAccess;

	/**
	 * Returns the {@link HarnessRunnerMaintenance} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code HarnessRunnerMaintenance} cannot initialize or fails to register access
	 */
	public static HarnessRunnerMaintenanceAccess getHarnessRunnerMaintenanceAccess()
	{
		HarnessRunnerMaintenanceAccess access = harnessRunnerMaintenanceAccess;
		if (access == null)
		{
			ensureClassInitialized(HarnessRunnerMaintenance.class);
			access = harnessRunnerMaintenanceAccess;
			if (access == null)
			{
				throw new AssertionError(HarnessRunnerMaintenance.class.getName() +
					" did not register HarnessRunnerMaintenanceAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link HarnessRunnerMaintenance}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setHarnessRunnerMaintenanceAccess(HarnessRunnerMaintenanceAccess access)
	{
		harnessRunnerMaintenanceAccess = access;
	}

	/**
	 * Initializes the class that registers the access point.
	 *
	 * @param owner the class to initialize
	 * @throws AssertionError if the class cannot be initialized
	 */
	private static void ensureClassInitialized(Class<?> owner)
	{
		try
		{
			MethodHandles.lookup().ensureInitialized(owner);
		}
		catch (IllegalAccessException e)
		{
			throw new AssertionError("Cannot initialize " + owner.getName(), e);
		}
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessRunnerMaintenanceSecrets()
	{
	}
}
