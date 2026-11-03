package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.LeftoverRuns;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link LeftoverRuns} construction required by tests.
 */
public final class LeftoverRunsSecrets
{
	private static LeftoverRunsAccess leftoverRunsAccess;

	/**
	 * Returns the {@link LeftoverRuns} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code LeftoverRuns} cannot initialize or fails to register access
	 */
	public static LeftoverRunsAccess getLeftoverRunsAccess()
	{
		LeftoverRunsAccess access = leftoverRunsAccess;
		if (access == null)
		{
			ensureClassInitialized(LeftoverRuns.class);
			access = leftoverRunsAccess;
			if (access == null)
				throw new AssertionError(LeftoverRuns.class.getName() + " did not register LeftoverRunsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link LeftoverRuns}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setLeftoverRunsAccess(LeftoverRunsAccess access)
	{
		leftoverRunsAccess = access;
	}

	/**
	 * Initializes the class that registers the access point.
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
	private LeftoverRunsSecrets()
	{
	}
}
