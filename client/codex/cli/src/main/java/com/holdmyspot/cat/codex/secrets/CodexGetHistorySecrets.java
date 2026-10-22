package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.CodexGetHistory;

/**
 * Exposes narrowly scoped {@link CodexGetHistory} access required by tests.
 */
public final class CodexGetHistorySecrets
{
	private static CodexGetHistoryAccess codexGetHistoryAccess;

	/**
	 * Returns the {@link CodexGetHistory} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code CodexGetHistory} cannot initialize or fails to register access
	 */
	public static CodexGetHistoryAccess getCodexGetHistoryAccess()
	{
		CodexGetHistoryAccess access = codexGetHistoryAccess;
		if (access == null)
		{
			ensureClassInitialized(CodexGetHistory.class);
			access = codexGetHistoryAccess;
			if (access == null)
				throw new AssertionError(CodexGetHistory.class.getName() + " did not register CodexGetHistoryAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link CodexGetHistory}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setCodexGetHistoryAccess(CodexGetHistoryAccess access)
	{
		codexGetHistoryAccess = access;
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
	private CodexGetHistorySecrets()
	{
	}
}
