package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessRunLock;

/**
 * Exposes narrowly scoped {@link HarnessRunLock} access required by tests.
 */
public final class HarnessRunLockSecrets
{
	private static HarnessRunLockAccess access;

	/**
	 * Returns the HarnessRunLock access point.
	 *
	 * @return the access point
	 */
	public static HarnessRunLockAccess access()
	{
		if (access == null)
			initialize(HarnessRunLock.class);
		if (access == null)
			throw new AssertionError("HarnessRunLock did not register its access point");
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
	 * Registers the HarnessRunLock access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(HarnessRunLockAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("HarnessRunLock access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessRunLockSecrets()
	{
	}
}
