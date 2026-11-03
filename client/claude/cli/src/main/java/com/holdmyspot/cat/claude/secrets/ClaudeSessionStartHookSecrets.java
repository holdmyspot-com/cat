package com.holdmyspot.cat.claude.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.claude.ClaudeSessionStartHook;

/**
 * Exposes narrowly scoped {@link ClaudeSessionStartHook} access required by tests.
 */
public final class ClaudeSessionStartHookSecrets
{
	private static ClaudeSessionStartHookAccess claudeSessionStartHookAccess;

	/**
	 * Returns the {@link ClaudeSessionStartHook} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ClaudeSessionStartHook} cannot initialize or fails to register access
	 */
	public static ClaudeSessionStartHookAccess getClaudeSessionStartHookAccess()
	{
		ClaudeSessionStartHookAccess access = claudeSessionStartHookAccess;
		if (access == null)
		{
			ensureClassInitialized(ClaudeSessionStartHook.class);
			access = claudeSessionStartHookAccess;
			if (access == null)
			{
				throw new AssertionError(ClaudeSessionStartHook.class.getName() +
					" did not register ClaudeSessionStartHookAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ClaudeSessionStartHook}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setClaudeSessionStartHookAccess(ClaudeSessionStartHookAccess access)
	{
		claudeSessionStartHookAccess = access;
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
	private ClaudeSessionStartHookSecrets()
	{
	}
}
