package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.launcher.client.ProcessHolderStarter;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link ProcessHolderStarter} construction required by tests.
 */
public final class ProcessHolderStarterSecrets
{
	private static ProcessHolderStarterAccess processHolderStarterAccess;

	/**
	 * Returns the {@link ProcessHolderStarter} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ProcessHolderStarter} cannot initialize or fails to register access
	 */
	public static ProcessHolderStarterAccess getProcessHolderStarterAccess()
	{
		ProcessHolderStarterAccess access = processHolderStarterAccess;
		if (access == null)
		{
			ensureClassInitialized(ProcessHolderStarter.class);
			access = processHolderStarterAccess;
			if (access == null)
			{
				throw new AssertionError(ProcessHolderStarter.class.getName() +
					" did not register ProcessHolderStarterAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ProcessHolderStarter}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setProcessHolderStarterAccess(ProcessHolderStarterAccess access)
	{
		processHolderStarterAccess = access;
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
	private ProcessHolderStarterSecrets()
	{
	}
}
