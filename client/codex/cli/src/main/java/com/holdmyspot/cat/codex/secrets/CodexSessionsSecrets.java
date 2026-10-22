package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;

/**
 * Exposes narrowly scoped {@link CodexSessions} access required by tests.
 */
public final class CodexSessionsSecrets
{
	private static CodexSessionsAccess codexSessionsAccess;

	/**
	 * Returns the {@link CodexSessions} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code CodexSessions} cannot initialize or fails to register access
	 */
	public static CodexSessionsAccess getCodexSessionsAccess()
	{
		CodexSessionsAccess access = codexSessionsAccess;
		if (access == null)
		{
			ensureClassInitialized(CodexSessions.class);
			access = codexSessionsAccess;
			if (access == null)
				throw new AssertionError(CodexSessions.class.getName() + " did not register CodexSessionsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link CodexSessions}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setCodexSessionsAccess(CodexSessionsAccess access)
	{
		codexSessionsAccess = access;
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
	private CodexSessionsSecrets()
	{
	}
}
