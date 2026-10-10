package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.history.SessionHistory;

/**
 * Exposes narrowly scoped {@link SessionHistory} access required by tests.
 */
public final class SessionHistorySecrets
{
	private static SessionHistoryAccess access;

	/**
	 * Returns the SessionHistory access point.
	 *
	 * @return the access point
	 */
	public static SessionHistoryAccess access()
	{
		if (access == null)
			initialize(SessionHistory.class);
		if (access == null)
			throw new AssertionError("SessionHistory did not register its access point");
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
	 * Registers the SessionHistory access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(SessionHistoryAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("SessionHistory access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private SessionHistorySecrets()
	{
	}
}
