package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessCapacity;

/**
 * Exposes narrowly scoped {@link HarnessCapacity} access required by tests.
 */
public final class HarnessCapacitySecrets
{
	private static HarnessCapacityAccess access;

	/**
	 * Returns the HarnessCapacity access point.
	 *
	 * @return the access point
	 */
	public static HarnessCapacityAccess access()
	{
		if (access == null)
			initialize(HarnessCapacity.class);
		if (access == null)
			throw new AssertionError("HarnessCapacity did not register its access point");
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
	 * Registers the HarnessCapacity access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(HarnessCapacityAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("HarnessCapacity access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessCapacitySecrets()
	{
	}
}
