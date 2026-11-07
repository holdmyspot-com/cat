package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.SessionIgnore;

import java.lang.invoke.MethodHandles;

/**
 * Exposes how {@link SessionIgnore} starts Git to the module's tests.
 */
public final class SessionIgnoreSecrets
{
	private static SessionIgnoreAccess sessionIgnoreAccess;

	/**
	 * Returns the {@link SessionIgnore} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code SessionIgnore} cannot initialize or fails to register access
	 */
	public static SessionIgnoreAccess getSessionIgnoreAccess()
	{
		SessionIgnoreAccess access = sessionIgnoreAccess;
		if (access == null)
		{
			ensureClassInitialized(SessionIgnore.class);
			access = sessionIgnoreAccess;
			if (access == null)
			{
				throw new AssertionError(SessionIgnore.class.getName() +
					" did not register SessionIgnoreAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link SessionIgnore}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setSessionIgnoreAccess(SessionIgnoreAccess access)
	{
		sessionIgnoreAccess = access;
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
	private SessionIgnoreSecrets()
	{
	}
}
