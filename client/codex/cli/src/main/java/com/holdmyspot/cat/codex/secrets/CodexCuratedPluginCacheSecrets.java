package com.holdmyspot.cat.codex.secrets;

import com.holdmyspot.cat.codex.runner.CodexCuratedPluginCache;

import java.lang.invoke.MethodHandles;

/**
 * Supplies the typed test access registered by {@link CodexCuratedPluginCache}.
 */
public final class CodexCuratedPluginCacheSecrets
{
	private static CodexCuratedPluginCacheAccess codexCuratedPluginCacheAccess;

	/**
	 * Returns the owning cache's test access.
	 *
	 * @return the registered access
	 * @throws AssertionError if the owner cannot initialize or fails to register access
	 */
	public static CodexCuratedPluginCacheAccess getCodexCuratedPluginCacheAccess()
	{
		CodexCuratedPluginCacheAccess access = codexCuratedPluginCacheAccess;
		if (access == null)
		{
			ensureClassInitialized(CodexCuratedPluginCache.class);
			access = codexCuratedPluginCacheAccess;
			if (access == null)
				throw new AssertionError(CodexCuratedPluginCache.class.getName() + " did not register cache access");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by the owner.
	 *
	 * @param access the owner's access implementation
	 */
	public static void setCodexCuratedPluginCacheAccess(CodexCuratedPluginCacheAccess access)
	{
		codexCuratedPluginCacheAccess = access;
	}

	/**
	 * Initializes the class that registers access.
	 *
	 * @param owner the class to initialize
	 * @throws AssertionError if initialization is inaccessible
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
	private CodexCuratedPluginCacheSecrets()
	{
	}
}
