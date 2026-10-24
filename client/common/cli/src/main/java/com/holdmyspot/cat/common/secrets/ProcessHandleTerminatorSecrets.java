package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator;

import java.lang.invoke.MethodHandles;

/**
 * Exposes narrowly scoped {@link ProcessHandleTerminator} construction required by tests.
 */
public final class ProcessHandleTerminatorSecrets
{
	private static ProcessHandleTerminatorAccess processHandleTerminatorAccess;

	/**
	 * Returns the {@link ProcessHandleTerminator} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ProcessHandleTerminator} cannot initialize or fails to register access
	 */
	public static ProcessHandleTerminatorAccess getProcessHandleTerminatorAccess()
	{
		ProcessHandleTerminatorAccess access = processHandleTerminatorAccess;
		if (access == null)
		{
			ensureClassInitialized(ProcessHandleTerminator.class);
			access = processHandleTerminatorAccess;
			if (access == null)
			{
				throw new AssertionError(ProcessHandleTerminator.class.getName() +
					" did not register ProcessHandleTerminatorAccess");
			}
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ProcessHandleTerminator}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setProcessHandleTerminatorAccess(ProcessHandleTerminatorAccess access)
	{
		processHandleTerminatorAccess = access;
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
	private ProcessHandleTerminatorSecrets()
	{
	}
}
