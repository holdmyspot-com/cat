package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;

import java.lang.invoke.MethodHandles;

/**
 * Exposes how {@link MainHarnessRunnerScope} paces its heartbeat to the module's tests.
 */
public final class MainHarnessRunnerScopeSecrets
{
	private static MainHarnessRunnerScopeAccess mainHarnessRunnerScopeAccess;

	/**
	 * Returns the {@link MainHarnessRunnerScope} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code MainHarnessRunnerScope} cannot initialize or fails to register access
	 */
	public static MainHarnessRunnerScopeAccess getMainHarnessRunnerScopeAccess()
	{
		MainHarnessRunnerScopeAccess access = mainHarnessRunnerScopeAccess;
		if (access == null)
		{
			ensureClassInitialized(MainHarnessRunnerScope.class);
			access = mainHarnessRunnerScopeAccess;
			if (access == null)
			{
				throw new AssertionError(MainHarnessRunnerScope.class.getName() +
					" did not register MainHarnessRunnerScopeAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link MainHarnessRunnerScope}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setMainHarnessRunnerScopeAccess(MainHarnessRunnerScopeAccess access)
	{
		mainHarnessRunnerScopeAccess = access;
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
	private MainHarnessRunnerScopeSecrets()
	{
	}
}
