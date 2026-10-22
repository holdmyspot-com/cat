package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.campaign.Round;

/**
 * Exposes narrowly scoped {@link Round} access required by tests.
 */
public final class RoundSecrets
{
	private static RoundAccess roundAccess;

	/**
	 * Returns the {@link Round} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code Round} cannot initialize or fails to register access
	 */
	public static RoundAccess getRoundAccess()
	{
		RoundAccess access = roundAccess;
		if (access == null)
		{
			ensureClassInitialized(Round.class);
			access = roundAccess;
			if (access == null)
				throw new AssertionError(Round.class.getName() + " did not register RoundAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link Round}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setRoundAccess(RoundAccess access)
	{
		roundAccess = access;
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
	private RoundSecrets()
	{
	}
}
