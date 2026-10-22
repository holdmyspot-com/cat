package com.holdmyspot.cat.claude.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.claude.optimizer.ClaudeSessions;

/**
 * Exposes narrowly scoped {@link ClaudeSessions} access required by tests.
 */
public final class ClaudeSessionsSecrets
{
	private static ClaudeSessionsAccess claudeSessionsAccess;

	/**
	 * Returns the {@link ClaudeSessions} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ClaudeSessions} cannot initialize or fails to register access
	 */
	public static ClaudeSessionsAccess getClaudeSessionsAccess()
	{
		ClaudeSessionsAccess access = claudeSessionsAccess;
		if (access == null)
		{
			ensureClassInitialized(ClaudeSessions.class);
			access = claudeSessionsAccess;
			if (access == null)
				throw new AssertionError(ClaudeSessions.class.getName() + " did not register ClaudeSessionsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ClaudeSessions}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setClaudeSessionsAccess(ClaudeSessionsAccess access)
	{
		claudeSessionsAccess = access;
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
	private ClaudeSessionsSecrets()
	{
	}
}
