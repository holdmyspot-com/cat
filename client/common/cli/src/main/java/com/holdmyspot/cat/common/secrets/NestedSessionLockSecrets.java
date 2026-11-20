package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.runner.NestedSessionLock;

/**
 * Exposes narrowly scoped {@link NestedSessionLock} access required by tests.
 */
public final class NestedSessionLockSecrets
{
	private static NestedSessionLockAccess nestedSessionLockAccess;

	/**
	 * Returns the {@link NestedSessionLock} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code NestedSessionLock} cannot initialize or fails to register access
	 */
	public static NestedSessionLockAccess getNestedSessionLockAccess()
	{
		NestedSessionLockAccess access = nestedSessionLockAccess;
		if (access == null)
		{
			ensureClassInitialized(NestedSessionLock.class);
			access = nestedSessionLockAccess;
			if (access == null)
				throw new AssertionError(NestedSessionLock.class.getName() + " did not register NestedSessionLockAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link NestedSessionLock}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setNestedSessionLockAccess(NestedSessionLockAccess access)
	{
		nestedSessionLockAccess = access;
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
	private NestedSessionLockSecrets()
	{
	}
}
