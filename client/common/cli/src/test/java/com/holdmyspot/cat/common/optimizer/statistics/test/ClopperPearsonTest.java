package com.holdmyspot.cat.common.optimizer.statistics.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.statistics.ClopperPearson;
import org.testng.annotations.Test;

/**
 * Tests {@link ClopperPearson}.
 */
public final class ClopperPearsonTest
{
	/**
	 * Creates the test.
	 */
	public ClopperPearsonTest()
	{
	}

	/**
	 * Verifies that 299 trials without an event are the fewest that put the upper bound within 1%, and 298 are not.
	 */
	@Test
	public void noEventsNeed299TrialsForOnePercent()
	{
		requireThat(ClopperPearson.upperBoundAtMost(0, 299, 0.01), "299").isTrue();
		requireThat(ClopperPearson.upperBoundAtMost(0, 298, 0.01), "298").isFalse();
	}

	/**
	 * Verifies that 59 trials without an event are the fewest that put the upper bound within 5%, and 58 are not.
	 */
	@Test
	public void noEventsNeed59TrialsForFivePercent()
	{
		requireThat(ClopperPearson.upperBoundAtMost(0, 59, 0.05), "59").isTrue();
		requireThat(ClopperPearson.upperBoundAtMost(0, 58, 0.05), "58").isFalse();
	}

	/**
	 * Verifies that one event within 400 trials leaves the upper bound above 1%, however the remaining trials go.
	 */
	@Test
	public void oneEventInFourHundredExceedsOnePercent()
	{
		requireThat(ClopperPearson.upperBoundAtMost(1, 400, 0.01), "1_of_400").isFalse();
	}

	/**
	 * Verifies that the fewest trials that bound one event within 1% is the exact binomial one, 473, and that the
	 * bound grows with the number of events.
	 */
	@Test
	public void eventsRaiseTheNeededTrials()
	{
		requireThat(ClopperPearson.upperBoundAtMost(1, 473, 0.01), "473").isTrue();
		requireThat(ClopperPearson.upperBoundAtMost(1, 472, 0.01), "472").isFalse();
		requireThat(ClopperPearson.upperBoundAtMost(2, 473, 0.01), "two_events").isFalse();
	}

	/**
	 * Verifies that an event count above the trials is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void moreEventsThanTrialsIsRefused()
	{
		ClopperPearson.upperBoundAtMost(3, 2, 0.01);
	}
}
