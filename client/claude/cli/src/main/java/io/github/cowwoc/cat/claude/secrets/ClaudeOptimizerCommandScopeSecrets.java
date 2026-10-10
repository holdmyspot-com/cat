package io.github.cowwoc.cat.claude.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.optimizer.ClaudeOptimizerCommandScope;

/**
 * Exposes narrowly scoped {@link ClaudeOptimizerCommandScope} access required by tests.
 */
public final class ClaudeOptimizerCommandScopeSecrets
{
	private static ClaudeOptimizerCommandScopeAccess access;

	/**
	 * Returns the ClaudeOptimizerCommandScope access point.
	 *
	 * @return the access point
	 */
	public static ClaudeOptimizerCommandScopeAccess access()
	{
		if (access == null)
			initialize(ClaudeOptimizerCommandScope.class);
		if (access == null)
			throw new AssertionError("ClaudeOptimizerCommandScope did not register its access point");
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
	 * Registers the ClaudeOptimizerCommandScope access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(ClaudeOptimizerCommandScopeAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("ClaudeOptimizerCommandScope access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ClaudeOptimizerCommandScopeSecrets()
	{
	}
}
