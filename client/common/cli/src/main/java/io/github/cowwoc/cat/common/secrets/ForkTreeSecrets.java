package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.workspace.ForkTree;

/**
 * Exposes narrowly scoped {@link ForkTree} access required by tests.
 */
public final class ForkTreeSecrets
{
	private static ForkTreeAccess access;

	/**
	 * Returns the ForkTree access point.
	 *
	 * @return the access point
	 */
	public static ForkTreeAccess access()
	{
		if (access == null)
			initialize(ForkTree.class);
		if (access == null)
			throw new AssertionError("ForkTree did not register its access point");
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
	 * Registers the ForkTree access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(ForkTreeAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("ForkTree access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ForkTreeSecrets()
	{
	}
}
