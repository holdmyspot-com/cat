package io.github.cowwoc.cat.claude.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.optimizer.ClaudeSessions;

/**
 * Exposes narrowly scoped {@link ClaudeSessions} access required by tests.
 */
public final class ClaudeSessionsSecrets
{
	private static ClaudeSessionsAccess access;

	/**
	 * Returns the ClaudeSessions access point.
	 *
	 * @return the access point
	 */
	public static ClaudeSessionsAccess access()
	{
		if (access == null)
			initialize(ClaudeSessions.class);
		if (access == null)
			throw new AssertionError("ClaudeSessions did not register its access point");
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
	 * Registers the ClaudeSessions access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(ClaudeSessionsAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("ClaudeSessions access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ClaudeSessionsSecrets()
	{
	}
}
