package com.holdmyspot.cat.common.optimizer.statistics.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.statistics.ExploratoryBounds;
import com.holdmyspot.cat.common.optimizer.statistics.Interval;
import org.testng.annotations.Test;

/**
 * Tests {@link ExploratoryBounds}.
 */
public final class ExploratoryBoundsTest
{
	/**
	 * Creates a test instance.
	 */
	public ExploratoryBoundsTest()
	{
	}

	/**
	 * Verifies 95% Wilson intervals against independently computed values.
	 */
	@Test
	public void wilsonMatchesReference()
	{
		// Requirement: R-S8
		requireInterval(ExploratoryBounds.wilson(99, 100), 0.945_513_803_821, 0.998_232_567_936, "99of100");
		requireInterval(ExploratoryBounds.wilson(50, 50), 0.928_652_400_867, 1, "50of50");
		requireInterval(ExploratoryBounds.wilson(0, 10), 0, 0.277_532_799_863, "0of10");
		requireInterval(ExploratoryBounds.wilson(45, 50), 0.786_397_685_625, 0.956_524_235_068, "45of50");
	}

	/**
	 * Verifies that the Wilson interval without trials is {@code [0, 1]}.
	 */
	@Test
	public void wilsonWithoutTrialsIsTrivial()
	{
		// Requirement: R-S8
		Interval interval = ExploratoryBounds.wilson(0, 0);
		requireThat(interval.lower(), "lower").isEqualTo(0.0);
		requireThat(interval.upper(), "upper").isEqualTo(1.0);
	}

	/**
	 * Requires an interval's endpoints within {@code 1e-9}, clamped to {@code [0, 1]}.
	 *
	 * @param interval the interval
	 * @param lower    the expected lower endpoint
	 * @param upper    the expected upper endpoint
	 * @param name     the name of the interval
	 */
	private static void requireInterval(Interval interval, double lower, double upper, String name)
	{
		requireThat(interval.lower(), name + "Lower").isBetween(Math.max(0, lower - 1.0e-9), true,
			lower + 1.0e-9, true);
		requireThat(interval.upper(), name + "Upper").isBetween(upper - 1.0e-9, true,
			Math.min(1, upper + 1.0e-9), true);
	}

	/**
	 * Verifies that more passes than trials are rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsPassesAboveTrials()
	{
		ExploratoryBounds.wilson(6, 5);
	}

	/**
	 * Verifies that an interval whose lower endpoint exceeds its upper endpoint is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsInvertedInterval()
	{
		new Interval(0.6, 0.5);
	}
}
