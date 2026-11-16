package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.workspace.Workspace;

/**
 * Exposes narrowly scoped {@link Workspace} access required by tests.
 */
public final class PluginCopiesSecrets
{
	private static PluginCopiesAccess pluginCopiesAccess;

	/**
	 * Returns the {@link Workspace} plugin-copying access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code Workspace} cannot initialize or fails to register access
	 */
	public static PluginCopiesAccess getPluginCopiesAccess()
	{
		PluginCopiesAccess access = pluginCopiesAccess;
		if (access == null)
		{
			ensureClassInitialized(Workspace.class);
			access = pluginCopiesAccess;
			if (access == null)
				throw new AssertionError(Workspace.class.getName() + " did not register PluginCopiesAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link Workspace}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setPluginCopiesAccess(PluginCopiesAccess access)
	{
		pluginCopiesAccess = access;
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
	private PluginCopiesSecrets()
	{
	}
}
