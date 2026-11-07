package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link ProcessHandleLiveness} construction required by tests.
 */
public final class ProcessHandleLivenessSecrets
{
	private static ProcessHandleLivenessAccess processHandleLivenessAccess;

	/**
	 * Returns the {@link ProcessHandleLiveness} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ProcessHandleLiveness} cannot initialize or fails to register access
	 */
	public static ProcessHandleLivenessAccess getProcessHandleLivenessAccess()
	{
		ProcessHandleLivenessAccess access = processHandleLivenessAccess;
		if (access == null)
		{
			ensureClassInitialized(ProcessHandleLiveness.class);
			access = processHandleLivenessAccess;
			if (access == null)
			{
				throw new AssertionError(ProcessHandleLiveness.class.getName() +
					" did not register ProcessHandleLivenessAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ProcessHandleLiveness}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setProcessHandleLivenessAccess(ProcessHandleLivenessAccess access)
	{
		processHandleLivenessAccess = access;
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
	private ProcessHandleLivenessSecrets()
	{
	}
}
