package com.holdmyspot.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Round;

/**
 * Exposes narrowly scoped {@link Round} access required by tests.
 */
public final class RoundSecrets
{
	private static RoundAccess access;

	/**
	 * Returns the Round access point.
	 *
	 * @return the access point
	 */
	public static RoundAccess access()
	{
		if (access == null)
			initialize(Round.class);
		if (access == null)
			throw new AssertionError("Round did not register its access point");
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
	 * Registers the Round access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(RoundAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("Round access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private RoundSecrets()
	{
	}
}
