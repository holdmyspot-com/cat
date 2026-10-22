package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.optimizer.CodexOptimizerCommandScope;

/**
 * Exposes narrowly scoped {@link CodexOptimizerCommandScope} access required by tests.
 */
public final class CodexOptimizerCommandScopeSecrets
{
	private static CodexOptimizerCommandScopeAccess codexOptimizerCommandScopeAccess;

	/**
	 * Returns the {@link CodexOptimizerCommandScope} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code CodexOptimizerCommandScope} cannot initialize or fails to register access
	 */
	public static CodexOptimizerCommandScopeAccess getCodexOptimizerCommandScopeAccess()
	{
		CodexOptimizerCommandScopeAccess access = codexOptimizerCommandScopeAccess;
		if (access == null)
		{
			ensureClassInitialized(CodexOptimizerCommandScope.class);
			access = codexOptimizerCommandScopeAccess;
			if (access == null)
				throw new AssertionError(CodexOptimizerCommandScope.class.getName() + " did not register " +
					"CodexOptimizerCommandScopeAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link CodexOptimizerCommandScope}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setCodexOptimizerCommandScopeAccess(CodexOptimizerCommandScopeAccess access)
	{
		codexOptimizerCommandScopeAccess = access;
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
	private CodexOptimizerCommandScopeSecrets()
	{
	}
}
