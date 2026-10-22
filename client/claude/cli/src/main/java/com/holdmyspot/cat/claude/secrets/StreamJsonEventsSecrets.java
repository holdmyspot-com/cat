package com.holdmyspot.cat.claude.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.claude.optimizer.StreamJsonEvents;

/**
 * Exposes narrowly scoped {@link StreamJsonEvents} access required by tests.
 */
public final class StreamJsonEventsSecrets
{
	private static StreamJsonEventsAccess streamJsonEventsAccess;

	/**
	 * Returns the {@link StreamJsonEvents} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code StreamJsonEvents} cannot initialize or fails to register access
	 */
	public static StreamJsonEventsAccess getStreamJsonEventsAccess()
	{
		StreamJsonEventsAccess access = streamJsonEventsAccess;
		if (access == null)
		{
			ensureClassInitialized(StreamJsonEvents.class);
			access = streamJsonEventsAccess;
			if (access == null)
				throw new AssertionError(StreamJsonEvents.class.getName() + " did not register StreamJsonEventsAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link StreamJsonEvents}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setStreamJsonEventsAccess(StreamJsonEventsAccess access)
	{
		streamJsonEventsAccess = access;
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
	private StreamJsonEventsSecrets()
	{
	}
}
