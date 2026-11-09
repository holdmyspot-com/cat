package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.installer.cli.internal.InstallerProcess;
import java.lang.invoke.MethodHandles;

/**
 * Supplies typed access to {@link InstallerProcess}'s controlled process start for its test modules.
 */
public final class InstallerProcessSecrets
{
	private static InstallerProcessAccess installerProcessAccess;

	/**
	 * Prevents construction of this static facade.
	 */
	private InstallerProcessSecrets()
	{
	}

	/**
	 * Registers access from the owning class's initializer.
	 *
	 * @param access the controlled process start
	 */
	public static void setInstallerProcessAccess(InstallerProcessAccess access)
	{
		installerProcessAccess = access;
	}

	/**
	 * Returns the registered access, initializing its owner when needed.
	 *
	 * @return the controlled process start
	 */
	public static InstallerProcessAccess getInstallerProcessAccess()
	{
		InstallerProcessAccess access = installerProcessAccess;
		if (access == null)
		{
			ensureClassInitialized(InstallerProcess.class);
			access = installerProcessAccess;
			if (access == null)
				throw new AssertionError(InstallerProcess.class.getName() + " did not register InstallerProcessAccess");
		}
		return access;
	}

	/**
	 * Initializes a class that registers an accessor.
	 *
	 * @param owner the class
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
}
