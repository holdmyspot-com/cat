package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.runner.HarnessCapacity;

/**
 * Exposes narrowly scoped {@link HarnessCapacity} access required by tests.
 */
public final class HarnessCapacitySecrets
{
	private static HarnessCapacityAccess harnessCapacityAccess;

	/**
	 * Returns the {@link HarnessCapacity} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code HarnessCapacity} cannot initialize or fails to register access
	 */
	public static HarnessCapacityAccess getHarnessCapacityAccess()
	{
		HarnessCapacityAccess access = harnessCapacityAccess;
		if (access == null)
		{
			ensureClassInitialized(HarnessCapacity.class);
			access = harnessCapacityAccess;
			if (access == null)
				throw new AssertionError(HarnessCapacity.class.getName() + " did not register HarnessCapacityAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link HarnessCapacity}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setHarnessCapacityAccess(HarnessCapacityAccess access)
	{
		harnessCapacityAccess = access;
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
	private HarnessCapacitySecrets()
	{
	}
}
