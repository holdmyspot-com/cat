package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.HarnessProjectRepository;

/**
 * Exposes narrowly scoped {@link HarnessProjectRepository} access required by tests.
 */
public final class HarnessProjectRepositorySecrets
{
	private static HarnessProjectRepositoryAccess access;

	/**
	 * Returns the HarnessProjectRepository access point.
	 *
	 * @return the access point
	 */
	public static HarnessProjectRepositoryAccess access()
	{
		if (access == null)
			initialize(HarnessProjectRepository.class);
		if (access == null)
			throw new AssertionError("HarnessProjectRepository did not register its access point");
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
	 * Registers the HarnessProjectRepository access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(HarnessProjectRepositoryAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("HarnessProjectRepository access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private HarnessProjectRepositorySecrets()
	{
	}
}
