package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.runner.HarnessProjectRepository;

/**
 * Exposes narrowly scoped {@link HarnessProjectRepository} access required by tests.
 */
public final class HarnessProjectRepositorySecrets
{
	private static HarnessProjectRepositoryAccess harnessProjectRepositoryAccess;

	/**
	 * Returns the {@link HarnessProjectRepository} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code HarnessProjectRepository} cannot initialize or fails to register access
	 */
	public static HarnessProjectRepositoryAccess getHarnessProjectRepositoryAccess()
	{
		HarnessProjectRepositoryAccess access = harnessProjectRepositoryAccess;
		if (access == null)
		{
			ensureClassInitialized(HarnessProjectRepository.class);
			access = harnessProjectRepositoryAccess;
			if (access == null)
				throw new AssertionError(HarnessProjectRepository.class.getName() + " did not register " +
					"HarnessProjectRepositoryAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link HarnessProjectRepository}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setHarnessProjectRepositoryAccess(HarnessProjectRepositoryAccess access)
	{
		harnessProjectRepositoryAccess = access;
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
	private HarnessProjectRepositorySecrets()
	{
	}
}
