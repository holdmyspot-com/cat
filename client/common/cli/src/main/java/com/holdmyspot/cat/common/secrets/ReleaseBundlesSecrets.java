package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.release.ReleaseBundles;

/**
 * Exposes narrowly scoped {@link ReleaseBundles} access required by tests.
 */
public final class ReleaseBundlesSecrets
{
	private static ReleaseBundlesAccess releaseBundlesAccess;

	/**
	 * Returns the {@link ReleaseBundles} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ReleaseBundles} cannot initialize or fails to register access
	 */
	public static ReleaseBundlesAccess getReleaseBundlesAccess()
	{
		ReleaseBundlesAccess access = releaseBundlesAccess;
		if (access == null)
		{
			ensureClassInitialized(ReleaseBundles.class);
			access = releaseBundlesAccess;
			if (access == null)
				throw new AssertionError(ReleaseBundles.class.getName() + " did not register ReleaseBundlesAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ReleaseBundles}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setReleaseBundlesAccess(ReleaseBundlesAccess access)
	{
		releaseBundlesAccess = access;
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
	private ReleaseBundlesSecrets()
	{
	}
}
