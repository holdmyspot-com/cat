package io.github.cowwoc.cat.distribution.bundle.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;

/**
 * Exposes narrowly scoped {@link JlinkImageBuilder} access required by tests.
 */
public final class JlinkImageBuilderSecrets
{
	private static JlinkImageBuilderAccess access;

	/**
	 * Returns the jlink-image-builder access point.
	 *
	 * @return the access point
	 */
	public static JlinkImageBuilderAccess access()
	{
		if (access == null)
			initialize(JlinkImageBuilder.class);
		if (access == null)
			throw new AssertionError("JlinkImageBuilder did not register its access point");
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
	 * Registers the jlink-image-builder access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(JlinkImageBuilderAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("JlinkImageBuilder access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private JlinkImageBuilderSecrets()
	{
	}
}
