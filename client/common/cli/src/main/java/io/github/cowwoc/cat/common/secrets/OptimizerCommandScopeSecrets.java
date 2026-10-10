package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.cli.OptimizerCommandScope;

/**
 * Exposes narrowly scoped {@link OptimizerCommandScope} access required by tests.
 */
public final class OptimizerCommandScopeSecrets
{
	private static OptimizerCommandScopeAccess access;

	/**
	 * Returns the OptimizerCommandScope access point.
	 *
	 * @return the access point
	 */
	public static OptimizerCommandScopeAccess access()
	{
		if (access == null)
			initialize(OptimizerCommandScope.class);
		if (access == null)
			throw new AssertionError("OptimizerCommandScope did not register its access point");
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
	 * Registers the OptimizerCommandScope access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(OptimizerCommandScopeAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("OptimizerCommandScope access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private OptimizerCommandScopeSecrets()
	{
	}
}
