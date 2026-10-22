package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.runner.HarnessOneShotOptions;

/**
 * Exposes narrowly scoped {@link HarnessOneShotOptions} access required by tests.
 */
public final class HarnessOneShotOptionsSecrets
{
	private static HarnessOneShotOptionsAccess harnessOneShotOptionsAccess;

	/**
	 * Returns the {@link HarnessOneShotOptions} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code HarnessOneShotOptions} cannot initialize or fails to register access
	 */
	public static HarnessOneShotOptionsAccess getHarnessOneShotOptionsAccess()
	{
		HarnessOneShotOptionsAccess access = harnessOneShotOptionsAccess;
		if (access == null)
		{
			ensureClassInitialized(HarnessOneShotOptions.class);
			access = harnessOneShotOptionsAccess;
			if (access == null)
				throw new AssertionError(HarnessOneShotOptions.class.getName() + " did not register " +
					"HarnessOneShotOptionsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link HarnessOneShotOptions}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setHarnessOneShotOptionsAccess(HarnessOneShotOptionsAccess access)
	{
		harnessOneShotOptionsAccess = access;
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
	private HarnessOneShotOptionsSecrets()
	{
	}
}
