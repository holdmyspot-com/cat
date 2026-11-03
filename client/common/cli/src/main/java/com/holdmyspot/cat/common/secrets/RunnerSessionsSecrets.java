package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;

/**
 * Exposes narrowly scoped {@link RunnerSessions} access required by tests.
 */
public final class RunnerSessionsSecrets
{
	private static RunnerSessionsAccess runnerSessionsAccess;

	/**
	 * Returns the {@link RunnerSessions} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code RunnerSessions} cannot initialize or fails to register access
	 */
	public static RunnerSessionsAccess getRunnerSessionsAccess()
	{
		RunnerSessionsAccess access = runnerSessionsAccess;
		if (access == null)
		{
			ensureClassInitialized(RunnerSessions.class);
			access = runnerSessionsAccess;
			if (access == null)
				throw new AssertionError(RunnerSessions.class.getName() + " did not register RunnerSessionsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link RunnerSessions}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setRunnerSessionsAccess(RunnerSessionsAccess access)
	{
		runnerSessionsAccess = access;
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
	private RunnerSessionsSecrets()
	{
	}
}
