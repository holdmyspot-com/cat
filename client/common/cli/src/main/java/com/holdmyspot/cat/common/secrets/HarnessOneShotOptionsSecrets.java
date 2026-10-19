package com.holdmyspot.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessOneShotOptions;

/**
 * Exposes narrowly scoped {@link HarnessOneShotOptions} access required by tests.
 */
public final class HarnessOneShotOptionsSecrets
{
	private static HarnessOneShotOptionsAccess access;

	/**
	 * Returns the HarnessOneShotOptions access point.
	 *
	 * @return the access point
	 */
	public static HarnessOneShotOptionsAccess access()
	{
		if (access == null)
			initialize(HarnessOneShotOptions.class);
		if (access == null)
			throw new AssertionError("HarnessOneShotOptions did not register its access point");
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
	 * Registers the HarnessOneShotOptions access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(HarnessOneShotOptionsAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("HarnessOneShotOptions access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessOneShotOptionsSecrets()
	{
	}
}
