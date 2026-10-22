package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.history.SessionHistory;

/**
 * Exposes narrowly scoped {@link SessionHistory} access required by tests.
 */
public final class SessionHistorySecrets
{
	private static SessionHistoryAccess sessionHistoryAccess;

	/**
	 * Returns the {@link SessionHistory} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code SessionHistory} cannot initialize or fails to register access
	 */
	public static SessionHistoryAccess getSessionHistoryAccess()
	{
		SessionHistoryAccess access = sessionHistoryAccess;
		if (access == null)
		{
			ensureClassInitialized(SessionHistory.class);
			access = sessionHistoryAccess;
			if (access == null)
				throw new AssertionError(SessionHistory.class.getName() + " did not register SessionHistoryAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link SessionHistory}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setSessionHistoryAccess(SessionHistoryAccess access)
	{
		sessionHistoryAccess = access;
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
	private SessionHistorySecrets()
	{
	}
}
