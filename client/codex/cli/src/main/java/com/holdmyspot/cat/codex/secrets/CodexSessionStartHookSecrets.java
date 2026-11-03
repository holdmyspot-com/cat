package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.CodexSessionStartHook;

/**
 * Exposes narrowly scoped {@link CodexSessionStartHook} access required by tests.
 */
public final class CodexSessionStartHookSecrets
{
	private static CodexSessionStartHookAccess codexSessionStartHookAccess;

	/**
	 * Returns the {@link CodexSessionStartHook} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code CodexSessionStartHook} cannot initialize or fails to register access
	 */
	public static CodexSessionStartHookAccess getCodexSessionStartHookAccess()
	{
		CodexSessionStartHookAccess access = codexSessionStartHookAccess;
		if (access == null)
		{
			ensureClassInitialized(CodexSessionStartHook.class);
			access = codexSessionStartHookAccess;
			if (access == null)
			{
				throw new AssertionError(CodexSessionStartHook.class.getName() +
					" did not register CodexSessionStartHookAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link CodexSessionStartHook}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setCodexSessionStartHookAccess(CodexSessionStartHookAccess access)
	{
		codexSessionStartHookAccess = access;
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
	private CodexSessionStartHookSecrets()
	{
	}
}
