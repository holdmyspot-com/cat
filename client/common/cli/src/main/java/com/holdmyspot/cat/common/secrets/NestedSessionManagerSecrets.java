package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.runner.NestedSessionManager;

/**
 * Exposes narrowly scoped {@link NestedSessionManager} access required by tests.
 */
public final class NestedSessionManagerSecrets
{
	private static NestedSessionManagerAccess nestedSessionManagerAccess;

	/**
	 * Returns the {@link NestedSessionManager} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code NestedSessionManager} cannot initialize or fails to register access
	 */
	public static NestedSessionManagerAccess getNestedSessionManagerAccess()
	{
		NestedSessionManagerAccess access = nestedSessionManagerAccess;
		if (access == null)
		{
			ensureClassInitialized(NestedSessionManager.class);
			access = nestedSessionManagerAccess;
			if (access == null)
				throw new AssertionError(NestedSessionManager.class.getName() + " did not register NestedSessionManagerAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link NestedSessionManager}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setNestedSessionManagerAccess(NestedSessionManagerAccess access)
	{
		nestedSessionManagerAccess = access;
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
	private NestedSessionManagerSecrets()
	{
	}
}
