package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessStandardError;

/**
 * Exposes narrowly scoped {@link HarnessStandardError} access required by tests.
 */
public final class HarnessStandardErrorSecrets
{
	private static HarnessStandardErrorAccess access;

	/**
	 * Returns the HarnessStandardError access point.
	 *
	 * @return the access point
	 */
	public static HarnessStandardErrorAccess access()
	{
		if (access == null)
			initialize(HarnessStandardError.class);
		if (access == null)
			throw new AssertionError("HarnessStandardError did not register its access point");
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
	 * Registers the HarnessStandardError access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(HarnessStandardErrorAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("HarnessStandardError access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessStandardErrorSecrets()
	{
	}
}
