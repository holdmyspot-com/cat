package io.github.cowwoc.cat.codex.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.optimizer.AppServerEvents;

/**
 * Exposes narrowly scoped {@link AppServerEvents} access required by tests.
 */
public final class AppServerEventsSecrets
{
	private static AppServerEventsAccess access;

	/**
	 * Returns the AppServerEvents access point.
	 *
	 * @return the access point
	 */
	public static AppServerEventsAccess access()
	{
		if (access == null)
			initialize(AppServerEvents.class);
		if (access == null)
			throw new AssertionError("AppServerEvents did not register its access point");
		return access;
	}

	/**
	 * Initializes a class that registers an access point.
	 *
	 * @param owner the class to initialize
	 */
	private static void initialize(Class<?> owner)
	{
		try
		{
			Class.forName(owner.getName(), true, owner.getClassLoader());
		}
		catch (ClassNotFoundException e)
		{
			throw new AssertionError("Unable to initialize " + owner.getSimpleName(), e);
		}
	}

	/**
	 * Registers the AppServerEvents access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(AppServerEventsAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("AppServerEvents access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private AppServerEventsSecrets()
	{
	}
}
