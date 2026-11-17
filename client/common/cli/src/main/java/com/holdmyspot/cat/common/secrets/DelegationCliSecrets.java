package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.delegation.DelegationCli;
import java.lang.invoke.MethodHandles;

/**
 * Exposes admission composition required by tests of {@link DelegationCli}.
 */
public final class DelegationCliSecrets
{
	private static DelegationCliAccess delegationCliAccess;

	/**
	 * Returns the CLI access point registered by {@link DelegationCli}.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code DelegationCli} cannot initialize or fails to register access
	 */
	public static DelegationCliAccess getDelegationCliAccess()
	{
		DelegationCliAccess access = delegationCliAccess;
		if (access == null)
		{
			ensureClassInitialized(DelegationCli.class);
			access = delegationCliAccess;
			if (access == null)
				throw new AssertionError(DelegationCli.class.getName() +
					" did not register DelegationCliAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link DelegationCli}.
	 *
	 * @param access implementation registered by the owning class
	 */
	public static void setDelegationCliAccess(DelegationCliAccess access)
	{
		delegationCliAccess = access;
	}

	/**
	 * Initializes the class that registers the access point.
	 *
	 * @param owner class to initialize
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
	private DelegationCliSecrets()
	{
	}
}
