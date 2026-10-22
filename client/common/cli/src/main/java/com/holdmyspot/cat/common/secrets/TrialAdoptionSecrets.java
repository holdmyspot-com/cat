package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.campaign.TrialAdoption;

/**
 * Exposes narrowly scoped {@link TrialAdoption} access required by tests.
 */
public final class TrialAdoptionSecrets
{
	private static TrialAdoptionAccess trialAdoptionAccess;

	/**
	 * Returns the {@link TrialAdoption} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code TrialAdoption} cannot initialize or fails to register access
	 */
	public static TrialAdoptionAccess getTrialAdoptionAccess()
	{
		TrialAdoptionAccess access = trialAdoptionAccess;
		if (access == null)
		{
			ensureClassInitialized(TrialAdoption.class);
			access = trialAdoptionAccess;
			if (access == null)
				throw new AssertionError(TrialAdoption.class.getName() + " did not register TrialAdoptionAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link TrialAdoption}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setTrialAdoptionAccess(TrialAdoptionAccess access)
	{
		trialAdoptionAccess = access;
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
	private TrialAdoptionSecrets()
	{
	}
}
