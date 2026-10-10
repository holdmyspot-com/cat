package io.github.cowwoc.cat.codex.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.optimizer.AppServerPromptSources;

/**
 * Exposes narrowly scoped {@link AppServerPromptSources} access required by tests.
 */
public final class AppServerPromptSourcesSecrets
{
	private static AppServerPromptSourcesAccess access;

	/**
	 * Returns the AppServerPromptSources access point.
	 *
	 * @return the access point
	 */
	public static AppServerPromptSourcesAccess access()
	{
		if (access == null)
			initialize(AppServerPromptSources.class);
		if (access == null)
			throw new AssertionError("AppServerPromptSources did not register its access point");
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
	 * Registers the AppServerPromptSources access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(AppServerPromptSourcesAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("AppServerPromptSources access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private AppServerPromptSourcesSecrets()
	{
	}
}
