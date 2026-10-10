package io.github.cowwoc.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.ExecutableFiles;

/**
 * Exposes narrowly scoped {@link ExecutableFiles} access required by tests.
 */
public final class ExecutableFilesSecrets
{
	private static ExecutableFilesAccess access;

	/**
	 * Returns the ExecutableFiles access point.
	 *
	 * @return the access point
	 */
	public static ExecutableFilesAccess access()
	{
		if (access == null)
			initialize(ExecutableFiles.class);
		if (access == null)
			throw new AssertionError("ExecutableFiles did not register its access point");
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
	 * Registers the ExecutableFiles access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(ExecutableFilesAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("ExecutableFiles access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private ExecutableFilesSecrets()
	{
	}
}
