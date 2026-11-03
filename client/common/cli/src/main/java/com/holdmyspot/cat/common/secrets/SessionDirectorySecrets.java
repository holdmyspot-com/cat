package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.SessionDirectory;

/**
 * Exposes narrowly scoped {@link SessionDirectory} access required by the tests of CAT's modules.
 */
public final class SessionDirectorySecrets
{
	private static SessionDirectoryAccess sessionDirectoryAccess;

	/**
	 * Returns the {@link SessionDirectory} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code SessionDirectory} cannot initialize or fails to register access
	 */
	public static SessionDirectoryAccess getSessionDirectoryAccess()
	{
		SessionDirectoryAccess access = sessionDirectoryAccess;
		if (access == null)
		{
			ensureClassInitialized(SessionDirectory.class);
			access = sessionDirectoryAccess;
			if (access == null)
				throw new AssertionError(SessionDirectory.class.getName() + " did not register SessionDirectoryAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link SessionDirectory}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setSessionDirectoryAccess(SessionDirectoryAccess access)
	{
		sessionDirectoryAccess = access;
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
	private SessionDirectorySecrets()
	{
	}
}
