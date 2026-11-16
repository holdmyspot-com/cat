package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.build.BuildAdmission;
import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link BuildAdmission} construction required by tests.
 */
public final class BuildAdmissionSecrets
{
	private static BuildAdmissionAccess buildAdmissionAccess;

	/**
	 * Returns the {@link BuildAdmission} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code BuildAdmission} cannot initialize or fails to register access
	 */
	public static BuildAdmissionAccess getBuildAdmissionAccess()
	{
		BuildAdmissionAccess access = buildAdmissionAccess;
		if (access == null)
		{
			ensureClassInitialized(BuildAdmission.class);
			access = buildAdmissionAccess;
			if (access == null)
				throw new AssertionError(BuildAdmission.class.getName() +
					" did not register BuildAdmissionAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link BuildAdmission}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setBuildAdmissionAccess(BuildAdmissionAccess access)
	{
		buildAdmissionAccess = access;
	}

	/**
	 * Initializes the class that registers the access point.
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
	private BuildAdmissionSecrets()
	{
	}
}
