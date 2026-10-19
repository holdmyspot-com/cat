package com.holdmyspot.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.TrialAdoption;

/**
 * Exposes narrowly scoped {@link TrialAdoption} access required by tests.
 */
public final class TrialAdoptionSecrets
{
	private static TrialAdoptionAccess access;

	/**
	 * Returns the TrialAdoption access point.
	 *
	 * @return the access point
	 */
	public static TrialAdoptionAccess access()
	{
		if (access == null)
			initialize(TrialAdoption.class);
		if (access == null)
			throw new AssertionError("TrialAdoption did not register its access point");
		return access;
	}

	/**
	 * Initializes a class that registers an access point.
	 *
	 * @param owner the class to initialize
	 */
	private static void initialize(Class<?> owner)
	{
		try
		{
			Class.forName(owner.getName(), true, owner.getClassLoader());
		}
		catch (ClassNotFoundException e)
		{
			throw new AssertionError("Unable to initialize " + owner.getSimpleName(), e);
		}
	}

	/**
	 * Registers the TrialAdoption access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(TrialAdoptionAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("TrialAdoption access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private TrialAdoptionSecrets()
	{
	}
}
