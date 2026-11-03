package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.CodexSubagentStartHook;

/**
 * Exposes narrowly scoped {@link CodexSubagentStartHook} access required by tests.
 */
public final class CodexSubagentStartHookSecrets
{
	private static CodexSubagentStartHookAccess codexSubagentStartHookAccess;

	/**
	 * Returns the {@link CodexSubagentStartHook} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code CodexSubagentStartHook} cannot initialize or fails to register access
	 */
	public static CodexSubagentStartHookAccess getCodexSubagentStartHookAccess()
	{
		CodexSubagentStartHookAccess access = codexSubagentStartHookAccess;
		if (access == null)
		{
			ensureClassInitialized(CodexSubagentStartHook.class);
			access = codexSubagentStartHookAccess;
			if (access == null)
			{
				throw new AssertionError(CodexSubagentStartHook.class.getName() +
					" did not register CodexSubagentStartHookAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link CodexSubagentStartHook}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setCodexSubagentStartHookAccess(CodexSubagentStartHookAccess access)
	{
		codexSubagentStartHookAccess = access;
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
	private CodexSubagentStartHookSecrets()
	{
	}
}
