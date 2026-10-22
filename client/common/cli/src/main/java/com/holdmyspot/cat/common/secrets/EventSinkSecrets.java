package com.holdmyspot.cat.common.secrets;

import java.lang.invoke.MethodHandles;

import com.holdmyspot.cat.common.optimizer.adapter.EventSink;

/**
 * Exposes narrowly scoped {@link EventSink} access required by tests.
 */
public final class EventSinkSecrets
{
	private static EventSinkAccess eventSinkAccess;

	/**
	 * Returns the {@link EventSink} access point.
	 *
	 * @return the access point
	 * @throws AssertionError if {@code EventSink} cannot initialize or fails to register access
	 */
	public static EventSinkAccess getEventSinkAccess()
	{
		EventSinkAccess access = eventSinkAccess;
		if (access == null)
		{
			ensureClassInitialized(EventSink.class);
			access = eventSinkAccess;
			if (access == null)
				throw new AssertionError(EventSink.class.getName() + " did not register EventSinkAccess");
		}
		return access;
	}

	/**
	 * Registers the implementation supplied by {@link EventSink}.
	 *
	 * @param access the implementation registered by the owning class
	 */
	public static void setEventSinkAccess(EventSinkAccess access)
	{
		eventSinkAccess = access;
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
	private EventSinkSecrets()
	{
	}
}
