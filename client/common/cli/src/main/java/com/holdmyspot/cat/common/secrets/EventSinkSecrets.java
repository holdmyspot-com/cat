package com.holdmyspot.cat.common.secrets;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.adapter.EventSink;

/**
 * Exposes narrowly scoped {@link EventSink} access required by tests.
 */
public final class EventSinkSecrets
{
	private static EventSinkAccess access;

	/**
	 * Returns the EventSink access point.
	 *
	 * @return the access point
	 */
	public static EventSinkAccess access()
	{
		if (access == null)
			initialize(EventSink.class);
		if (access == null)
			throw new AssertionError("EventSink did not register its access point");
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
	 * Registers the EventSink access point.
	 *
	 * @param newAccess the access point
	 */
	public static void setAccess(EventSinkAccess newAccess)
	{
		requireThat(newAccess, "newAccess").isNotNull();
		if (access != null)
			throw new IllegalStateException("EventSink access point is already registered");
		access = newAccess;
	}

	/**
	 * Prevents utility-class construction.
	 */
	private EventSinkSecrets()
	{
	}
}
