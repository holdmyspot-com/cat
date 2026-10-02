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
	 * Verifies that rejections without a reset time back off for 1, 2, 4, 8, 16 and then at most 30 minutes, and
	 * that a completed trial restarts the sequence.
	 */
	@Test
	public void rejectionBacksOffExponentially()
	{
		// Requirement: R-E6
		Throttle throttle = Throttle.NONE;
		long[] minutes = {1, 2, 4, 8, 16, 30, 30};
		for (int i = 0; i < minutes.length; ++i)
		{
			throttle = throttle.reject(NOW, null);
			requireThat(throttle.holdUntil(), "holdUntil[" + i + "]").
				isEqualTo(NOW.plus(Duration.ofMinutes(minutes[i])));
			requireThat(throttle.limit(16), "limit[" + i + "]").isEqualTo(1);
		}
		throttle = throttle.afterCompletion().reject(NOW, null);
		requireThat(throttle.holdUntil(), "afterCompletion").isEqualTo(NOW.plus(Duration.ofMinutes(1)));
		requireThat(Throttle.MAXIMUM_BACKOFF, "MAXIMUM_BACKOFF").isEqualTo(Duration.ofMinutes(30));
	}

	/**
	 * Verifies that a rejection with a reset time waits until that time.
	 */
	@Test
	public void rejectionWaitsForReportedReset()
	{
		// Requirement: R-E6
		Instant reset = NOW.plusSeconds(10);
		Throttle throttle = Throttle.NONE.reject(NOW, reset);
		requireThat(throttle.holdUntil(), "holdUntil").isEqualTo(reset);
		requireThat(throttle.allowsAdmission(reset.minusMillis(1)), "beforeReset").isFalse();
		requireThat(throttle.allowsAdmission(reset), "atReset").isTrue();
	}

	/**
	 * Verifies that a rate-limited trial reruns as the same logical trial after the wait, without counting as an
	 * infrastructure failure.
	 */
	@Test
	public void rejectedTrialRerunsAfterWait()
	{
		// Requirement: R-E6
		Campaign campaign = new Builder().confirmed().slowStart(new SlowStart(4, 1)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey key = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		Instant reset = NOW.plusSeconds(60);
		campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.RATE_LIMITED,
			"rate limit reached", NOW, reset));

		LogicalTrial trial = campaign.trials().getFirst();
		requireThat(trial.state(), "state").isEqualTo(TrialState.ASSIGNED);
		requireThat(trial.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
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
