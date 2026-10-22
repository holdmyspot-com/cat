package com.holdmyspot.cat.distribution.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.distribution.DistributionMain;

/**
 * Exposes narrowly scoped {@link DistributionMain} access required by tests.
 */
public final class DistributionMainSecrets
{
	private static DistributionMainAccess distributionMainAccess;

	/**
	 * Returns the {@link DistributionMain} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code DistributionMain} cannot initialize or fails to register access
	 */
	public static DistributionMainAccess getDistributionMainAccess()
	{
		DistributionMainAccess access = distributionMainAccess;
		if (access == null)
		{
			ensureClassInitialized(DistributionMain.class);
			access = distributionMainAccess;
			if (access == null)
				throw new AssertionError(DistributionMain.class.getName() + " did not register DistributionMainAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link DistributionMain}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setDistributionMainAccess(DistributionMainAccess access)
	{
		distributionMainAccess = access;
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
	private DistributionMainSecrets()
	{
	}
}
