package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import java.lang.invoke.MethodHandles;

/**
 * Supplies typed access to {@link StartupCacheTrainer}'s tree-removal boundary for its test module.
 */
public final class StartupCacheTrainerSecrets
{
	private static StartupCacheTrainerAccess startupCacheTrainerAccess;

	/**
	 * Prevents construction of this static facade.
	 */
	private StartupCacheTrainerSecrets()
	{
	}

	/**
	 * Registers access from the owning class's initializer.
	 *
	 * @param access the tree-removal boundary
	 */
	public static void setStartupCacheTrainerAccess(StartupCacheTrainerAccess access)
	{
		startupCacheTrainerAccess = access;
	}

	/**
	 * Returns the registered access, initializing its owner when needed.
	 *
	 * @return the access
	 */
	public static StartupCacheTrainerAccess getStartupCacheTrainerAccess()
	{
		StartupCacheTrainerAccess access = startupCacheTrainerAccess;
		if (access == null)
		{
			try
			{
				MethodHandles.lookup().ensureInitialized(StartupCacheTrainer.class);
			}
			catch (IllegalAccessException failure)
			{
				throw new AssertionError("Cannot initialize " + StartupCacheTrainer.class.getName(), failure);
			}
			access = startupCacheTrainerAccess;
			if (access == null)
			{
				throw new AssertionError(StartupCacheTrainer.class.getName() +
					" did not register StartupCacheTrainerAccess");
			}
		}
		return access;
	}
}
