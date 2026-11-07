package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.launcher.SessionLauncherStart;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link SessionLauncherStart} process starts required by tests.
 */
public final class SessionLauncherStartSecrets
{
	private static SessionLauncherStartAccess sessionLauncherStartAccess;

	/**
	 * Returns the {@link SessionLauncherStart} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code SessionLauncherStart} cannot initialize or fails to register access
	 */
	public static SessionLauncherStartAccess getSessionLauncherStartAccess()
	{
		SessionLauncherStartAccess access = sessionLauncherStartAccess;
		if (access == null)
		{
			ensureClassInitialized(SessionLauncherStart.class);
			access = sessionLauncherStartAccess;
			if (access == null)
			{
				throw new AssertionError(SessionLauncherStart.class.getName() +
					" did not register SessionLauncherStartAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link SessionLauncherStart}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setSessionLauncherStartAccess(SessionLauncherStartAccess access)
	{
		sessionLauncherStartAccess = access;
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
	private SessionLauncherStartSecrets()
	{
	}
}
