package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;

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
		Throttle below = Throttle.NONE.observe(List.of(window(0.79)), NOW, 4);
		requireThat(below.limit(16), "below.limit").isEqualTo(16);
		requireThat(below.allowsAdmission(NOW), "below.allowsAdmission").isTrue();

		Throttle at = Throttle.NONE.observe(List.of(window(0.10), window(0.80)), NOW, 4);
		requireThat(at.limit(16), "at.limit").isEqualTo(4);
		requireThat(at.limit(2), "at.limit(2)").isEqualTo(2);
		requireThat(at.allowsAdmission(NOW), "at.allowsAdmission").isTrue();

		Throttle frozen = at.observe(List.of(window(0.90)), NOW, 4);
		requireThat(frozen.limit(16), "frozen.limit").isEqualTo(4);

		Throttle released = at.observe(List.of(window(0.50)), NOW, 4);
		requireThat(released.limit(16), "released.limit").isEqualTo(16);
	}

	/**
	 * Verifies that 95% utilization drops the window to 1 and holds admissions until the reported reset time, and
	 * that 94% does not.
	 */
	@Test
	public void ninetyFivePercentHoldsUntilReset()
	{
		// Requirement: R-E6
		Throttle below = Throttle.NONE.observe(List.of(window(0.94)), NOW, 6);
		requireThat(below.limit(16), "below.limit").isEqualTo(6);
		requireThat(below.allowsAdmission(NOW), "below.allowsAdmission").isTrue();

		Instant reset = NOW.plusSeconds(90);
		Throttle at = Throttle.NONE.observe(List.of(new RateLimitWindow(0.95, reset)), NOW, 6);
		requireThat(at.limit(16), "at.limit").isEqualTo(1);
		requireThat(at.allowsAdmission(reset.minusMillis(1)), "beforeReset").isFalse();
		requireThat(at.allowsAdmission(reset), "atReset").isTrue();
	}

	/**
	 * Verifies that 95% utilization without a reset time drops the window to 1 without holding admissions, because the
	 * throttle keeps no backoff sequence of its own.
	 */
	@Test
	public void nearLimitWithoutResetSetsNoHold()
	{
		// Requirement: R-E6
		Throttle throttle = Throttle.NONE.observe(List.of(window(0.97)), NOW, 6);
		requireThat(throttle, "throttle").isEqualTo(new Throttle(Instant.EPOCH, 1, 0));
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

		Instant reset = NOW.plusSeconds(30);
		campaign = Transitions.apply(campaign, new CampaignEvent.ObserveRateLimit(
			List.of(new RateLimitWindow(0.97, reset)), NOW));
		requireThat(campaign.throttle().holdUntil(), "holdUntil").isEqualTo(reset);
		requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(1);
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}
}
