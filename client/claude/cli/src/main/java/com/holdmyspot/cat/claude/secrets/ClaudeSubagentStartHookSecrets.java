package com.holdmyspot.cat.claude.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.claude.ClaudeSubagentStartHook;

/**
 * Exposes narrowly scoped {@link ClaudeSubagentStartHook} access required by tests.
 */
public final class ClaudeSubagentStartHookSecrets
{
	private static ClaudeSubagentStartHookAccess claudeSubagentStartHookAccess;

	/**
	 * Returns the {@link ClaudeSubagentStartHook} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ClaudeSubagentStartHook} cannot initialize or fails to register access
	 */
	public static ClaudeSubagentStartHookAccess getClaudeSubagentStartHookAccess()
	{
		ClaudeSubagentStartHookAccess access = claudeSubagentStartHookAccess;
		if (access == null)
		{
			ensureClassInitialized(ClaudeSubagentStartHook.class);
			access = claudeSubagentStartHookAccess;
			if (access == null)
			{
				throw new AssertionError(ClaudeSubagentStartHook.class.getName() +
					" did not register ClaudeSubagentStartHookAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ClaudeSubagentStartHook}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setClaudeSubagentStartHookAccess(ClaudeSubagentStartHookAccess access)
	{
		claudeSubagentStartHookAccess = access;
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
	private ClaudeSubagentStartHookSecrets()
	{
	}
}
