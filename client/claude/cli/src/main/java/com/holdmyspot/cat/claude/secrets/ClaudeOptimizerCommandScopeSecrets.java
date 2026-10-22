package com.holdmyspot.cat.claude.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.claude.optimizer.ClaudeOptimizerCommandScope;

/**
 * Exposes narrowly scoped {@link ClaudeOptimizerCommandScope} access required by tests.
 */
public final class ClaudeOptimizerCommandScopeSecrets
{
	private static ClaudeOptimizerCommandScopeAccess claudeOptimizerCommandScopeAccess;

	/**
	 * Returns the {@link ClaudeOptimizerCommandScope} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ClaudeOptimizerCommandScope} cannot initialize or fails to register access
	 */
	public static ClaudeOptimizerCommandScopeAccess getClaudeOptimizerCommandScopeAccess()
	{
		ClaudeOptimizerCommandScopeAccess access = claudeOptimizerCommandScopeAccess;
		if (access == null)
		{
			ensureClassInitialized(ClaudeOptimizerCommandScope.class);
			access = claudeOptimizerCommandScopeAccess;
			if (access == null)
				throw new AssertionError(ClaudeOptimizerCommandScope.class.getName() + " did not register " +
					"ClaudeOptimizerCommandScopeAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ClaudeOptimizerCommandScope}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setClaudeOptimizerCommandScopeAccess(ClaudeOptimizerCommandScopeAccess access)
	{
		claudeOptimizerCommandScopeAccess = access;
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
	private ClaudeOptimizerCommandScopeSecrets()
	{
	}
}
