package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.build.BuildAdmissionCli;
import java.lang.invoke.MethodHandles;

/**
 * Exposes narrow CLI operations required by build-admission tests.
 */
public final class BuildAdmissionCliSecrets
{
	private static BuildAdmissionCliAccess buildAdmissionCliAccess;

	/**
	 * Returns the CLI access point registered by {@link BuildAdmissionCli}.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code BuildAdmissionCli} cannot initialize or fails to register access
	 */
	public static BuildAdmissionCliAccess getBuildAdmissionCliAccess()
	{
		BuildAdmissionCliAccess access = buildAdmissionCliAccess;
		if (access == null)
		{
			ensureClassInitialized(BuildAdmissionCli.class);
			access = buildAdmissionCliAccess;
			if (access == null)
				throw new AssertionError(BuildAdmissionCli.class.getName() +
					" did not register BuildAdmissionCliAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link BuildAdmissionCli}.
	 *
	 * @param access implementation registered by the owning class
	 */
	public static void setBuildAdmissionCliAccess(BuildAdmissionCliAccess access)
	{
		buildAdmissionCliAccess = access;
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
	private BuildAdmissionCliSecrets()
	{
	}
}
