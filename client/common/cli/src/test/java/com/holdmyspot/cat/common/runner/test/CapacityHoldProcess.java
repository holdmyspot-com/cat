package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Records a capacity hold in a separate JVM for {@link HarnessCapacityTest}.
 */
public final class CapacityHoldProcess
{
	/**
	 * Prevents instantiation.
	 */
	private CapacityHoldProcess()
	{
	}

	/**
	 * Records a hold with an isolated directory and controlled clock, and prints its end.
	 *
	 * @param args the directory and starting instant
	 * @throws IOException if capacity files cannot be used
	 */
	public static void main(String[] args) throws IOException
	{
		ManualClock clock = new ManualClock(Instant.parse(args[1]));
		HarnessCapacity capacity = HarnessCapacity.of(Path.of(args[0]), 1, () ->
		{
			throw new AssertionError("The fixture must not wait");
		}, clock);
		System.out.println(capacity.reject(null).endsAt());
	}
}
