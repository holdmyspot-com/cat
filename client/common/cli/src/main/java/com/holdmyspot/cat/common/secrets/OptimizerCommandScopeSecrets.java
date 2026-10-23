package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.cli.AbstractOptimizerCommandScope;

/**
 * Exposes narrowly scoped {@link AbstractOptimizerCommandScope} access required by tests.
 */
public final class OptimizerCommandScopeSecrets
{
	private static OptimizerCommandScopeAccess optimizerCommandScopeAccess;

	/**
	 * Returns the {@link AbstractOptimizerCommandScope} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code AbstractOptimizerCommandScope} cannot initialize or fails to register access
	 */
	public static OptimizerCommandScopeAccess getOptimizerCommandScopeAccess()
	{
		OptimizerCommandScopeAccess access = optimizerCommandScopeAccess;
		if (access == null)
		{
			ensureClassInitialized(AbstractOptimizerCommandScope.class);
			access = optimizerCommandScopeAccess;
			if (access == null)
				throw new AssertionError(AbstractOptimizerCommandScope.class.getName() + " did not register " +
					"OptimizerCommandScopeAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link AbstractOptimizerCommandScope}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setOptimizerCommandScopeAccess(OptimizerCommandScopeAccess access)
	{
		optimizerCommandScopeAccess = access;
	}

	/**
	 * Initializes a class that registers an access point.
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
	private OptimizerCommandScopeSecrets()
	{
	}
}
