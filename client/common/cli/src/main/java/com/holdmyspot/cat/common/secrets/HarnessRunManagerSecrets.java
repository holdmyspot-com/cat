package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.runner.HarnessRunManager;

/**
 * Exposes narrowly scoped {@link HarnessRunManager} access required by tests.
 */
public final class HarnessRunManagerSecrets
{
	private static HarnessRunManagerAccess harnessRunManagerAccess;

	/**
	 * Returns the {@link HarnessRunManager} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code HarnessRunManager} cannot initialize or fails to register access
	 */
	public static HarnessRunManagerAccess getHarnessRunManagerAccess()
	{
		HarnessRunManagerAccess access = harnessRunManagerAccess;
		if (access == null)
		{
			ensureClassInitialized(HarnessRunManager.class);
			access = harnessRunManagerAccess;
			if (access == null)
				throw new AssertionError(HarnessRunManager.class.getName() + " did not register HarnessRunManagerAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link HarnessRunManager}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setHarnessRunManagerAccess(HarnessRunManagerAccess access)
	{
		harnessRunManagerAccess = access;
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
	private HarnessRunManagerSecrets()
	{
	}
}
