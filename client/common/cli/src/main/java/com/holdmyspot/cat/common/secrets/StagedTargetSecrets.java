package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.StagedTarget;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link StagedTarget} access required by tests.
 */
public final class StagedTargetSecrets
{
	private static StagedTargetAccess stagedTargetAccess;

	/**
	 * Returns the {@link StagedTarget} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code StagedTarget} cannot initialize or fails to register access
	 */
	public static StagedTargetAccess getStagedTargetAccess()
	{
		StagedTargetAccess access = stagedTargetAccess;
		if (access == null)
		{
			ensureClassInitialized(StagedTarget.class);
			access = stagedTargetAccess;
			if (access == null)
				throw new AssertionError(StagedTarget.class.getName() + " did not register StagedTargetAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link StagedTarget}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setStagedTargetAccess(StagedTargetAccess access)
	{
		stagedTargetAccess = access;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private StagedTargetSecrets()
	{
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
}
