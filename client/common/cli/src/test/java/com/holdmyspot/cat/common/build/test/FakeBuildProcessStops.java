package com.holdmyspot.cat.common.build.test;

import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Dispatches each accepted forced-stop request only to that process's controlled lifecycle callback.
 */
public final class FakeBuildProcessStops implements LongConsumer
{
	private final Map<Long, Runnable> stops;

	/**
	 * Creates a dispatcher for positive synthetic process identifiers.
	 *
	 * @param stops callback for each controlled process
	 * @throws NullPointerException if the map, a process identifier, or a callback is null
	 * @throws IllegalArgumentException if a process identifier is not positive
	 */
	public FakeBuildProcessStops(Map<Long, Runnable> stops)
	{
		this.stops = Map.copyOf(stops);
		for (long pid : this.stops.keySet())
			if (pid <= 0)
				throw new IllegalArgumentException("Process identifier must be positive");
	}

	/**
	 * Requests a stop for the registered process, if one exists.
	 *
	 * @param pid process identifier
	 */
	@Override
	public void accept(long pid)
	{
		Runnable stop = stops.get(pid);
		if (stop != null)
			stop.run();
	}
}
