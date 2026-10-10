package io.github.cowwoc.cat.claude.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.optimizer.ClaudeProfileInventory;

/**
 * Exposes narrowly scoped {@link ClaudeProfileInventory} access required by tests.
 */
public final class ClaudeProfileInventorySecrets
{
	private static ClaudeProfileInventoryAccess access;

	/**
	 * Returns the ClaudeProfileInventory access point.
	 *
	 * @return the access point
	 */
	public static ClaudeProfileInventoryAccess access()
	{
		if (access == null)
			initialize(ClaudeProfileInventory.class);
		if (access == null)
			throw new AssertionError("ClaudeProfileInventory did not register its access point");
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
	 * Registers the ClaudeProfileInventory access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(ClaudeProfileInventoryAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("ClaudeProfileInventory access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ClaudeProfileInventorySecrets()
	{
	}
}
