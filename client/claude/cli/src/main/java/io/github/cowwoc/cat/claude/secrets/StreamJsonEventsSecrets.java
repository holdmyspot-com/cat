package io.github.cowwoc.cat.claude.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.optimizer.StreamJsonEvents;

/**
 * Exposes narrowly scoped {@link StreamJsonEvents} access required by tests.
 */
public final class StreamJsonEventsSecrets
{
	private static StreamJsonEventsAccess access;

	/**
	 * Returns the StreamJsonEvents access point.
	 *
	 * @return the access point
	 */
	public static StreamJsonEventsAccess access()
	{
		if (access == null)
			initialize(StreamJsonEvents.class);
		if (access == null)
			throw new AssertionError("StreamJsonEvents did not register its access point");
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
	 * Registers the StreamJsonEvents access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(StreamJsonEventsAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("StreamJsonEvents access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private StreamJsonEventsSecrets()
	{
	}
}
