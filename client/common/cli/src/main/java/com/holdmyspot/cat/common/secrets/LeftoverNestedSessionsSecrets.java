package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.LeftoverNestedSessions;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link LeftoverNestedSessions} construction required by tests.
 */
public final class LeftoverNestedSessionsSecrets
{
	private static LeftoverNestedSessionsAccess leftoverNestedSessionsAccess;

	/**
	 * Returns the {@link LeftoverNestedSessions} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code LeftoverNestedSessions} cannot initialize or fails to register access
	 */
	public static LeftoverNestedSessionsAccess getLeftoverNestedSessionsAccess()
	{
		LeftoverNestedSessionsAccess access = leftoverNestedSessionsAccess;
		if (access == null)
		{
			ensureClassInitialized(LeftoverNestedSessions.class);
			access = leftoverNestedSessionsAccess;
			if (access == null)
				throw new AssertionError(LeftoverNestedSessions.class.getName() +
					" did not register LeftoverNestedSessionsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link LeftoverNestedSessions}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setLeftoverNestedSessionsAccess(LeftoverNestedSessionsAccess access)
	{
		leftoverNestedSessionsAccess = access;
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
	private LeftoverNestedSessionsSecrets()
	{
	}
}
