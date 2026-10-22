package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.runner.HarnessRunLock;

/**
 * Exposes narrowly scoped {@link HarnessRunLock} access required by tests.
 */
public final class HarnessRunLockSecrets
{
	private static HarnessRunLockAccess harnessRunLockAccess;

	/**
	 * Returns the {@link HarnessRunLock} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code HarnessRunLock} cannot initialize or fails to register access
	 */
	public static HarnessRunLockAccess getHarnessRunLockAccess()
	{
		HarnessRunLockAccess access = harnessRunLockAccess;
		if (access == null)
		{
			ensureClassInitialized(HarnessRunLock.class);
			access = harnessRunLockAccess;
			if (access == null)
				throw new AssertionError(HarnessRunLock.class.getName() + " did not register HarnessRunLockAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link HarnessRunLock}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setHarnessRunLockAccess(HarnessRunLockAccess access)
	{
		harnessRunLockAccess = access;
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
	private HarnessRunLockSecrets()
	{
	}
}
