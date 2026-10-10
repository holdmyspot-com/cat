package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessRunManager;

/**
 * Exposes narrowly scoped {@link HarnessRunManager} access required by tests.
 */
public final class HarnessRunManagerSecrets
{
	private static HarnessRunManagerAccess access;

	/**
	 * Returns the HarnessRunManager access point.
	 *
	 * @return the access point
	 */
	public static HarnessRunManagerAccess access()
	{
		if (access == null)
			initialize(HarnessRunManager.class);
		if (access == null)
			throw new AssertionError("HarnessRunManager did not register its access point");
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
	 * Registers the HarnessRunManager access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(HarnessRunManagerAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("HarnessRunManager access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessRunManagerSecrets()
	{
	}
}
