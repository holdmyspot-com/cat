package com.holdmyspot.cat.common.optimizer.store.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.store.TrialSession;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;

/**
 * Tests the record of one trial session.
 */
public final class TrialSessionTest
{
	/**
	 * Verifies that a session whose end time precedes its start time, as when the wall clock steps backwards during
	 * the session, is recorded with both times as observed.
	 */
	@Test
	public void keepsTimesAfterClockStepsBack()
	{
		// Requirement: R-E6
		Instant startedAt = Instant.parse("2026-10-05T12:00:05Z");
		Instant endedAt = Instant.parse("2026-10-05T12:00:00Z");
		TrialSession session = new TrialSession(startedAt, endedAt, 1, Throttle.NONE, List.of(), HostLogin.API_KEY,
			null);
		requireThat(session.startedAt(), "startedAt").isEqualTo(startedAt);
		requireThat(session.endedAt(), "endedAt").isEqualTo(endedAt);
	}
}
