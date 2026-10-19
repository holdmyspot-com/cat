package com.holdmyspot.cat.distribution.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.distribution.DistributionMain;

/**
 * Exposes narrowly scoped {@link DistributionMain} access required by tests.
 */
public final class DistributionMainSecrets
{
	private static DistributionMainAccess access;

	/**
	 * Returns the distribution-main access point.
	 *
	 * @return the access point
	 */
	public static DistributionMainAccess access()
	{
		if (access == null)
			initialize(DistributionMain.class);
		if (access == null)
			throw new AssertionError("DistributionMain did not register its access point");
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
	 * Registers the distribution-main access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(DistributionMainAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("DistributionMain access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private DistributionMainSecrets()
	{
	}
}
