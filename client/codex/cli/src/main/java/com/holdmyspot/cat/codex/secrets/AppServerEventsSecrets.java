package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.optimizer.AppServerEvents;

/**
 * Exposes narrowly scoped {@link AppServerEvents} access required by tests.
 */
public final class AppServerEventsSecrets
{
	private static AppServerEventsAccess appServerEventsAccess;

	/**
	 * Returns the {@link AppServerEvents} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code AppServerEvents} cannot initialize or fails to register access
	 */
	public static AppServerEventsAccess getAppServerEventsAccess()
	{
		AppServerEventsAccess access = appServerEventsAccess;
		if (access == null)
		{
			ensureClassInitialized(AppServerEvents.class);
			access = appServerEventsAccess;
			if (access == null)
				throw new AssertionError(AppServerEvents.class.getName() + " did not register AppServerEventsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link AppServerEvents}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setAppServerEventsAccess(AppServerEventsAccess access)
	{
		appServerEventsAccess = access;
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
	private AppServerEventsSecrets()
	{
	}
}
