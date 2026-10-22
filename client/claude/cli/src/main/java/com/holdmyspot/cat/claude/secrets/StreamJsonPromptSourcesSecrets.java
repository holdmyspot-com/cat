package com.holdmyspot.cat.claude.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.claude.optimizer.StreamJsonPromptSources;

/**
 * Exposes narrowly scoped {@link StreamJsonPromptSources} access required by tests.
 */
public final class StreamJsonPromptSourcesSecrets
{
	private static StreamJsonPromptSourcesAccess streamJsonPromptSourcesAccess;

	/**
	 * Returns the {@link StreamJsonPromptSources} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code StreamJsonPromptSources} cannot initialize or fails to register access
	 */
	public static StreamJsonPromptSourcesAccess getStreamJsonPromptSourcesAccess()
	{
		StreamJsonPromptSourcesAccess access = streamJsonPromptSourcesAccess;
		if (access == null)
		{
			ensureClassInitialized(StreamJsonPromptSources.class);
			access = streamJsonPromptSourcesAccess;
			if (access == null)
				throw new AssertionError(StreamJsonPromptSources.class.getName() + " did not register " +
					"StreamJsonPromptSourcesAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link StreamJsonPromptSources}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setStreamJsonPromptSourcesAccess(StreamJsonPromptSourcesAccess access)
	{
		streamJsonPromptSourcesAccess = access;
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
	private StreamJsonPromptSourcesSecrets()
	{
	}
}
