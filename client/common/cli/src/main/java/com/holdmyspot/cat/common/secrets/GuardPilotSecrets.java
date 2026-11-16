package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.GuardPilot;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link GuardPilot} construction required by tests.
 */
public final class GuardPilotSecrets
{
	private static GuardPilotAccess guardPilotAccess;

	/**
	 * Returns the {@link GuardPilot} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code GuardPilot} cannot initialize or fails to register access
	 */
	public static GuardPilotAccess getGuardPilotAccess()
	{
		GuardPilotAccess access = guardPilotAccess;
		if (access == null)
		{
			ensureClassInitialized(GuardPilot.class);
			access = guardPilotAccess;
			if (access == null)
				throw new AssertionError(GuardPilot.class.getName() + " did not register GuardPilotAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link GuardPilot}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setGuardPilotAccess(GuardPilotAccess access)
	{
		guardPilotAccess = access;
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
	private GuardPilotSecrets()
	{
	}
}
