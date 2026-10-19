package com.holdmyspot.cat.claude.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.optimizer.StreamJsonPromptSources;

/**
 * Exposes narrowly scoped {@link StreamJsonPromptSources} access required by tests.
 */
public final class StreamJsonPromptSourcesSecrets
{
	private static StreamJsonPromptSourcesAccess access;

	/**
	 * Returns the StreamJsonPromptSources access point.
	 *
	 * @return the access point
	 */
	public static StreamJsonPromptSourcesAccess access()
	{
		if (access == null)
			initialize(StreamJsonPromptSources.class);
		if (access == null)
			throw new AssertionError("StreamJsonPromptSources did not register its access point");
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
	 * Registers the StreamJsonPromptSources access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(StreamJsonPromptSourcesAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("StreamJsonPromptSources access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private StreamJsonPromptSourcesSecrets()
	{
	}
}
