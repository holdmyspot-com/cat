package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.ConcurrencyPolicy;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.RunFailure;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.SlowStart;
import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link Throttle}.
 */
public final class ThrottleTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

	/**
	 * Creates the test.
	 */
	public ThrottleTest()
	{
	}

	/**
	 * Returns a window without a reset time.
	 *
	 * @param utilization the reported utilization
	 * @return the window
	 */
	private static RateLimitWindow window(double utilization)
	{
		return new RateLimitWindow(utilization, null);
	}

	/**
	 * Verifies that utilization below 80% leaves growth unrestricted, and that 80% stops the window from growing.
	 */
	@Test
	public void eightyPercentStopsGrowth()
	{
		// Requirement: R-E6
		Throttle below = Throttle.NONE.observe(List.of(window(0.79)), 4);
		requireThat(below.limit(16), "below.limit").isEqualTo(16);
		requireThat(below.allowsAdmission(NOW), "below.allowsAdmission").isTrue();

		Throttle at = Throttle.NONE.observe(List.of(window(0.10), window(0.80)), 4);
		requireThat(at.limit(16), "at.limit").isEqualTo(4);
		requireThat(at.limit(2), "at.limit(2)").isEqualTo(2);
		requireThat(at.allowsAdmission(NOW), "at.allowsAdmission").isTrue();

		Throttle frozen = at.observe(List.of(window(0.90)), 4);
		requireThat(frozen.limit(16), "frozen.limit").isEqualTo(4);

		Throttle released = at.observe(List.of(window(0.50)), 4);
		requireThat(released.limit(16), "released.limit").isEqualTo(16);
	}

	/**
	 * Verifies that utilization near or at the limit stops the window from growing without lowering it or holding
	 * admissions, because only a rejection shows that the harness cannot serve another session: a provider pool can
	 * report one exhausted account while another serves the next request.
	 */
	@Test
	public void exhaustedWindowSetsNoHold()
	{
		// Requirement: R-E6
		Instant reset = NOW.plusSeconds(90);
		Throttle throttle = Throttle.NONE.observe(List.of(new RateLimitWindow(1.0, reset), window(0.97)), 6);
		requireThat(throttle, "throttle").isEqualTo(new Throttle(Instant.EPOCH, 6, 0));
		requireThat(throttle.allowsAdmission(NOW), "allowsAdmission").isTrue();
	}

	/**
	 * Verifies that the harness's shared hold drops the window to 1, holds admissions until it ends, sets the backoff
	 * count, and never moves the end of a longer hold earlier.
	 */
	@Test
	public void sharedHoldHoldsAdmissions()
	{
		// Requirement: R-E6
		Instant end = NOW.plus(Duration.ofMinutes(4));
		Throttle held = Throttle.NONE.hold(end, 3);
		requireThat(held, "held").isEqualTo(new Throttle(end, 1, 3));
		requireThat(held.allowsAdmission(end.minusMillis(1)), "beforeEnd").isFalse();
		requireThat(held.allowsAdmission(end), "atEnd").isTrue();

		Throttle earlier = held.hold(NOW.plus(Duration.ofMinutes(1)), 4);
		requireThat(earlier, "earlier").isEqualTo(new Throttle(end, 1, 4));
	}

	/**
	 * Verifies that a rate-limited trial reruns as the same logical trial after the shared hold ends, without counting
	 * as an infrastructure failure.
	 */
	@Test
	public void rejectedTrialRerunsAfterWait()
	{
		// Requirement: R-E6
		Campaign campaign = new Builder().confirmed().slowStart(new SlowStart(4, 1)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey key = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		Instant reset = NOW.plus(Duration.ofMinutes(5));
		campaign = Transitions.apply(campaign, new CampaignEvent.HoldAdmissions(reset, 2));
		campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.RATE_LIMITED,
			"rate limit reached", NOW));

		LogicalTrial trial = campaign.trials().getFirst();
		requireThat(trial.state(), "state").isEqualTo(TrialState.ASSIGNED);
		requireThat(trial.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
		requireThat(campaign.throttle(), "throttle").isEqualTo(new Throttle(reset, 1, 2));
		requireThat(campaign.slowStart(), "slowStart").isEqualTo(SlowStart.INITIAL);
		requireThat(Scheduler.plan(campaign, reset.minusMillis(1), 8), "beforeReset").isEmpty();
		requireThat(Scheduler.plan(campaign, reset, 8), "atReset").
			containsExactly(List.of(new Admission.Rerun(key)));
	}

	/**
	 * Verifies that reported utilization reaches the campaign's throttle state.
	 */
	@Test
	public void observedUtilizationReachesCampaign()
	{
		// Requirement: R-E6
		Campaign campaign = new Builder().confirmed().development("formatting", 50, 0).
			slowStart(new SlowStart(4, 0)).build();
		campaign = Transitions.apply(campaign, new CampaignEvent.ObserveRateLimit(List.of(window(0.85)),
			NOW));
		requireThat(campaign.throttle().windowCap(), "windowCap").isEqualTo(4);

		campaign = Transitions.apply(campaign, new CampaignEvent.ObserveRateLimit(
			List.of(new RateLimitWindow(0.97, NOW.plusSeconds(30))), NOW));
		requireThat(campaign.throttle().allowsAdmission(NOW), "allowsAdmission").isTrue();
		requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(4);
	}
}
