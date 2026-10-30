package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.Platform;

/**
 * Exposes narrowly scoped {@link Platform} access required by tests.
 */
public final class PlatformSecrets
{
	private static PlatformAccess platformAccess;

	/**
	 * Returns the {@link Platform} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code Platform} cannot initialize or fails to register access
	 */
	public static PlatformAccess getPlatformAccess()
	{
		PlatformAccess access = platformAccess;
		if (access == null)
		{
			ensureClassInitialized(Platform.class);
			access = platformAccess;
			if (access == null)
				throw new AssertionError(Platform.class.getName() + " did not register PlatformAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link Platform}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setPlatformAccess(PlatformAccess access)
	{
		platformAccess = access;
	}

	/**
	 * Initializes a class that registers an access point.
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
	private PlatformSecrets()
	{
	}
}
