package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.WaitUntil;

/**
 * Exposes narrowly scoped {@link WaitUntil} access required by tests.
 */
public final class WaitUntilSecrets
{
	private static WaitUntilAccess waitUntilAccess;

	/**
	 * Returns the {@link WaitUntil} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code WaitUntil} cannot initialize or fails to register access
	 */
	public static WaitUntilAccess getWaitUntilAccess()
	{
		WaitUntilAccess access = waitUntilAccess;
		if (access == null)
		{
			ensureClassInitialized(WaitUntil.class);
			access = waitUntilAccess;
			if (access == null)
				throw new AssertionError(WaitUntil.class.getName() + " did not register WaitUntilAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link WaitUntil}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setWaitUntilAccess(WaitUntilAccess access)
	{
		waitUntilAccess = access;
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
	private WaitUntilSecrets()
	{
	}
}
