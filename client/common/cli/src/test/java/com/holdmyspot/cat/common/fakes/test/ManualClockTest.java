package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.testng.annotations.Test;

/**
 * Tests that zone views of the manually advanced clock preserve its shared time.
 */
public final class ManualClockTest
{
	/**
	 * Creates the test.
	 */
	public ManualClockTest()
	{
	}

	/**
	 * Verifies that changing the zone preserves the instant and subsequent manual time changes.
	 */
	@Test
	public void zoneViewsShareTime()
	{
		Instant start = Instant.parse("2026-10-01T00:00:00Z");
		ManualClock clock = new ManualClock(start);
		ZoneOffset zone = ZoneOffset.ofHours(2);
		Clock view = clock.withZone(zone);
		requireThat(view.getZone(), "zone").isEqualTo(zone);
		requireThat(view.instant(), "instant").isEqualTo(start);
		clock.advance(Duration.ofMinutes(1));
		requireThat(view.instant(), "advancedInstant").isEqualTo(clock.instant());
		Clock utc = view.withZone(ZoneOffset.UTC);
		requireThat(utc.getZone(), "zone").isEqualTo(ZoneOffset.UTC);
		clock.set(start);
		requireThat(utc.instant(), "resetInstant").isEqualTo(start);
	}

	/**
	 * Verifies that the inherited clock boundary rejects a missing zone.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsMissingZone()
	{
		new ManualClock(Instant.EPOCH).withZone(null);
	}
}
