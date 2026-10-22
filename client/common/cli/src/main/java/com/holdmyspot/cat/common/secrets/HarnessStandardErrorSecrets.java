package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.runner.HarnessStandardError;

/**
 * Exposes narrowly scoped {@link HarnessStandardError} access required by tests.
 */
public final class HarnessStandardErrorSecrets
{
	private static HarnessStandardErrorAccess harnessStandardErrorAccess;

	/**
	 * Returns the {@link HarnessStandardError} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code HarnessStandardError} cannot initialize or fails to register access
	 */
	public static HarnessStandardErrorAccess getHarnessStandardErrorAccess()
	{
		HarnessStandardErrorAccess access = harnessStandardErrorAccess;
		if (access == null)
		{
			ensureClassInitialized(HarnessStandardError.class);
			access = harnessStandardErrorAccess;
			if (access == null)
				throw new AssertionError(HarnessStandardError.class.getName() + " did not register HarnessStandardErrorAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link HarnessStandardError}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setHarnessStandardErrorAccess(HarnessStandardErrorAccess access)
	{
		harnessStandardErrorAccess = access;
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
	private HarnessStandardErrorSecrets()
	{
	}
}
