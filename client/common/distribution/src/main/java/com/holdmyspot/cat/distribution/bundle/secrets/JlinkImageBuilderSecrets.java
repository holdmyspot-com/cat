package com.holdmyspot.cat.distribution.bundle.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.distribution.bundle.JlinkImageBuilder;

/**
 * Exposes narrowly scoped {@link JlinkImageBuilder} access required by tests.
 */
public final class JlinkImageBuilderSecrets
{
	private static JlinkImageBuilderAccess jlinkImageBuilderAccess;

	/**
	 * Returns the {@link JlinkImageBuilder} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code JlinkImageBuilder} cannot initialize or fails to register access
	 */
	public static JlinkImageBuilderAccess getJlinkImageBuilderAccess()
	{
		JlinkImageBuilderAccess access = jlinkImageBuilderAccess;
		if (access == null)
		{
			ensureClassInitialized(JlinkImageBuilder.class);
			access = jlinkImageBuilderAccess;
			if (access == null)
				throw new AssertionError(JlinkImageBuilder.class.getName() + " did not register JlinkImageBuilderAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link JlinkImageBuilder}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setJlinkImageBuilderAccess(JlinkImageBuilderAccess access)
	{
		jlinkImageBuilderAccess = access;
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
	private JlinkImageBuilderSecrets()
	{
	}
}
