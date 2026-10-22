package com.holdmyspot.cat.claude.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.claude.optimizer.ClaudeProfileInventory;

/**
 * Exposes narrowly scoped {@link ClaudeProfileInventory} access required by tests.
 */
public final class ClaudeProfileInventorySecrets
{
	private static ClaudeProfileInventoryAccess claudeProfileInventoryAccess;

	/**
	 * Returns the {@link ClaudeProfileInventory} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ClaudeProfileInventory} cannot initialize or fails to register access
	 */
	public static ClaudeProfileInventoryAccess getClaudeProfileInventoryAccess()
	{
		ClaudeProfileInventoryAccess access = claudeProfileInventoryAccess;
		if (access == null)
		{
			ensureClassInitialized(ClaudeProfileInventory.class);
			access = claudeProfileInventoryAccess;
			if (access == null)
				throw new AssertionError(ClaudeProfileInventory.class.getName() + " did not register " +
					"ClaudeProfileInventoryAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ClaudeProfileInventory}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setClaudeProfileInventoryAccess(ClaudeProfileInventoryAccess access)
	{
		claudeProfileInventoryAccess = access;
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
	private ClaudeProfileInventorySecrets()
	{
	}
}
