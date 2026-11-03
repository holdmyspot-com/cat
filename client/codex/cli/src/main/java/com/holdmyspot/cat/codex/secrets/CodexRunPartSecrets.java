package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.CodexRunPart;

/**
 * Exposes narrowly scoped {@link CodexRunPart} access required by tests.
 */
public final class CodexRunPartSecrets
{
	private static CodexRunPartAccess codexRunPartAccess;

	/**
	 * Returns the {@link CodexRunPart} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code CodexRunPart} cannot initialize or fails to register access
	 */
	public static CodexRunPartAccess getCodexRunPartAccess()
	{
		CodexRunPartAccess access = codexRunPartAccess;
		if (access == null)
		{
			ensureClassInitialized(CodexRunPart.class);
			access = codexRunPartAccess;
			if (access == null)
				throw new AssertionError(CodexRunPart.class.getName() + " did not register CodexRunPartAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link CodexRunPart}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setCodexRunPartAccess(CodexRunPartAccess access)
	{
		codexRunPartAccess = access;
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
	private CodexRunPartSecrets()
	{
	}
}
