package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.workspace.StagedSources;

/**
 * Exposes narrowly scoped {@link StagedSources} access required by tests.
 */
public final class StagedSourcesSecrets
{
	private static StagedSourcesAccess stagedSourcesAccess;

	/**
	 * Returns the {@link StagedSources} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code StagedSources} cannot initialize or fails to register access
	 */
	public static StagedSourcesAccess getStagedSourcesAccess()
	{
		StagedSourcesAccess access = stagedSourcesAccess;
		if (access == null)
		{
			ensureClassInitialized(StagedSources.class);
			access = stagedSourcesAccess;
			if (access == null)
				throw new AssertionError(StagedSources.class.getName() + " did not register StagedSourcesAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link StagedSources}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setStagedSourcesAccess(StagedSourcesAccess access)
	{
		stagedSourcesAccess = access;
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
	private StagedSourcesSecrets()
	{
	}
}
