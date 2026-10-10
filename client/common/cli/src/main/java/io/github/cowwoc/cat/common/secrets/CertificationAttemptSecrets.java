package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;

/**
 * Exposes narrowly scoped {@link CertificationAttempt} access required by tests.
 */
public final class CertificationAttemptSecrets
{
	private static CertificationAttemptAccess access;

	/**
	 * Returns the CertificationAttempt access point.
	 *
	 * @return the access point
	 */
	public static CertificationAttemptAccess access()
	{
		if (access == null)
			initialize(CertificationAttempt.class);
		if (access == null)
			throw new AssertionError("CertificationAttempt did not register its access point");
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
	 * Registers the CertificationAttempt access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(CertificationAttemptAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("CertificationAttempt access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private CertificationAttemptSecrets()
	{
	}
}
