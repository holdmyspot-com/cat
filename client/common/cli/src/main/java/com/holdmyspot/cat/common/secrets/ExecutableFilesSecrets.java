package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.ExecutableFiles;

/**
 * Exposes narrowly scoped {@link ExecutableFiles} access required by tests.
 */
public final class ExecutableFilesSecrets
{
	private static ExecutableFilesAccess executableFilesAccess;

	/**
	 * Returns the {@link ExecutableFiles} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code ExecutableFiles} cannot initialize or fails to register access
	 */
	public static ExecutableFilesAccess getExecutableFilesAccess()
	{
		ExecutableFilesAccess access = executableFilesAccess;
		if (access == null)
		{
			ensureClassInitialized(ExecutableFiles.class);
			access = executableFilesAccess;
			if (access == null)
				throw new AssertionError(ExecutableFiles.class.getName() + " did not register ExecutableFilesAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link ExecutableFiles}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setExecutableFilesAccess(ExecutableFilesAccess access)
	{
		executableFilesAccess = access;
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
	private ExecutableFilesSecrets()
	{
	}
}
