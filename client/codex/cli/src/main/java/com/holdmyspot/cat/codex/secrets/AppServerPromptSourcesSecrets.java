package com.holdmyspot.cat.codex.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.codex.optimizer.AppServerPromptSources;

/**
 * Exposes narrowly scoped {@link AppServerPromptSources} access required by tests.
 */
public final class AppServerPromptSourcesSecrets
{
	private static AppServerPromptSourcesAccess appServerPromptSourcesAccess;

	/**
	 * Returns the {@link AppServerPromptSources} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code AppServerPromptSources} cannot initialize or fails to register access
	 */
	public static AppServerPromptSourcesAccess getAppServerPromptSourcesAccess()
	{
		AppServerPromptSourcesAccess access = appServerPromptSourcesAccess;
		if (access == null)
		{
			ensureClassInitialized(AppServerPromptSources.class);
			access = appServerPromptSourcesAccess;
			if (access == null)
				throw new AssertionError(AppServerPromptSources.class.getName() + " did not register " +
					"AppServerPromptSourcesAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link AppServerPromptSources}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setAppServerPromptSourcesAccess(AppServerPromptSourcesAccess access)
	{
		appServerPromptSourcesAccess = access;
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
	private AppServerPromptSourcesSecrets()
	{
	}
}
