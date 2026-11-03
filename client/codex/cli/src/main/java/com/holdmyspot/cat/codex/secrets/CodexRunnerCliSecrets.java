package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.runner.CodexRunnerCli;

/**
 * Exposes narrowly scoped {@link CodexRunnerCli} access required by tests.
 */
public final class CodexRunnerCliSecrets
{
	private static CodexRunnerCliAccess codexRunnerCliAccess;

	/**
	 * Returns the {@link CodexRunnerCli} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code CodexRunnerCli} cannot initialize or fails to register access
	 */
	public static CodexRunnerCliAccess getCodexRunnerCliAccess()
	{
		CodexRunnerCliAccess access = codexRunnerCliAccess;
		if (access == null)
		{
			ensureClassInitialized(CodexRunnerCli.class);
			access = codexRunnerCliAccess;
			if (access == null)
				throw new AssertionError(CodexRunnerCli.class.getName() + " did not register CodexRunnerCliAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link CodexRunnerCli}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setCodexRunnerCliAccess(CodexRunnerCliAccess access)
	{
		codexRunnerCliAccess = access;
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
	private CodexRunnerCliSecrets()
	{
	}
}
