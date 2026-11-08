package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.cli.Commands;

/**
 * Exposes narrowly scoped {@link Commands} access required by tests.
 */
public final class CommandsSecrets
{
	private static CommandsAccess commandsAccess;

	/**
	 * Returns the {@link Commands} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code Commands} cannot initialize or fails to register access
	 */
	public static CommandsAccess getCommandsAccess()
	{
		CommandsAccess access = commandsAccess;
		if (access == null)
		{
			ensureClassInitialized(Commands.class);
			access = commandsAccess;
			if (access == null)
				throw new AssertionError(Commands.class.getName() + " did not register " +
					"CommandsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link Commands}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setCommandsAccess(CommandsAccess access)
	{
		commandsAccess = access;
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
	private CommandsSecrets()
	{
	}
}
