package com.holdmyspot.cat.codex.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.optimizer.CodexOptimizerCommandScope;

/**
 * Exposes narrowly scoped {@link CodexOptimizerCommandScope} access required by tests.
 */
public final class CodexOptimizerCommandScopeSecrets
{
	private static CodexOptimizerCommandScopeAccess access;

	/**
	 * Returns the CodexOptimizerCommandScope access point.
	 *
	 * @return the access point
	 */
	public static CodexOptimizerCommandScopeAccess access()
	{
		if (access == null)
			initialize(CodexOptimizerCommandScope.class);
		if (access == null)
			throw new AssertionError("CodexOptimizerCommandScope did not register its access point");
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
	 * Registers the CodexOptimizerCommandScope access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(CodexOptimizerCommandScopeAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("CodexOptimizerCommandScope access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private CodexOptimizerCommandScopeSecrets()
	{
	}
}
