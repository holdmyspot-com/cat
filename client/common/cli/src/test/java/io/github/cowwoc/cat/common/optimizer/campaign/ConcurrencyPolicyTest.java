package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;

import java.time.Instant;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link ConcurrencyPolicy} and {@link SlowStart}.
 */
public final class ConcurrencyPolicyTest
{
	/**
	 * Creates the test.
	 */
	public ConcurrencyPolicyTest()
	{
	}

	/**
	 * Returns sampled outcome counts that keep or increase the permitted number of concurrent trials.
	 *
	 * @return rows of outcomes, failures and the expected ceiling
	 */
	@DataProvider
	public static Object[][] riskCeilings()
	{
		return new Object[][]
			{
				{0, 0, 1},
				{10, 0, 1},
				{11, 0, 2},
				{22, 0, 3},
				{23, 0, 4},
				{46, 0, 7},
				{47, 0, 8},
				{100, 1, 10},
				{100, 100, 1}
			};
	}

	/**
	 * Verifies the risk ceiling at each threshold.
	 *
	 * @param outcomes the adopted outcomes
	 * @param failures the adopted failures
	 * @param expected the expected ceiling
	 */
	@Test(dataProvider = "riskCeilings")
	public void riskCeilingMatchesThresholds(int outcomes, int failures, int expected)
	{
		// Requirement: R-E4
		requireThat(ConcurrencyPolicy.riskCeiling(outcomes, failures), "riskCeiling").isEqualTo(expected);
	}

	/**
	 * Verifies that slow start doubles after each window whose trials all pass, up to its maximum.
	 */
	@Test
	public void slowStartDoublesAfterPassingWindow()
	{
		// Requirement: R-E4
		SlowStart state = SlowStart.INITIAL;
		state = state.afterPass();
		requireThat(state, "afterOnePass").isEqualTo(new SlowStart(2, 0));
		state = state.afterPass();
		requireThat(state, "afterHalfWindow").isEqualTo(new SlowStart(2, 1));
		state = state.afterPass();
		requireThat(state, "afterWindow").isEqualTo(new SlowStart(4, 0));
		requireThat(new SlowStart(16, 15).afterPass(), "atMaximum").isEqualTo(new SlowStart(16, 0));
	}

	/**
	 * Verifies that a failure resets the window to 1.
	 */
	@Test
	public void failureResetsWindow()
	{
		// Requirement: R-E4
		requireThat(new SlowStart(8, 3).afterFailure(), "afterFailure").isEqualTo(SlowStart.INITIAL);
		requireThat(ConcurrencyPolicy.window(100, 1, new SlowStart(8, 3).afterFailure(), 16, Throttle.NONE),
			"window").isEqualTo(1);
	}

	/**
	 * Verifies that the window is the smallest of the risk ceiling, slow start, campaign ceiling and throttle limit.
	 */
	@Test
	public void windowIsSmallestLimit()
	{
		// Requirement: R-E4
		SlowStart sixteen = new SlowStart(16, 0);
		requireThat(ConcurrencyPolicy.window(47, 0, sixteen, 16, Throttle.NONE), "riskBound").isEqualTo(8);
		requireThat(ConcurrencyPolicy.window(100, 0, new SlowStart(4, 0), 16, Throttle.NONE), "slowStartBound").
			isEqualTo(4);
		requireThat(ConcurrencyPolicy.window(100, 0, sixteen, 3, Throttle.NONE), "ceilingBound").isEqualTo(3);
		requireThat(ConcurrencyPolicy.window(100, 0, sixteen, 16, new Throttle(Instant.EPOCH, 2, 0)),
			"throttleBound").isEqualTo(2);
	}

	/**
	 * Verifies that the category limit equals the passes still required to certify, and is at least 1 below the
	 * cap and 0 at the cap.
	 */
	@Test
	public void categoryLimitFollowsRequiredPasses()
	{
		// Requirement: R-E4
		DecisionBoundaries boundaries = Boundaries.standard(2);
		requireThat(ConcurrencyPolicy.categoryLimit(boundaries, CampaignBuilder.counts(0, 0)), "empty").
			isEqualTo(100);
		requireThat(ConcurrencyPolicy.categoryLimit(boundaries, CampaignBuilder.counts(97, 0)), "threeShort").
			isEqualTo(3);
		requireThat(ConcurrencyPolicy.categoryLimit(boundaries, CampaignBuilder.counts(99, 0)), "oneShort").
			isEqualTo(1);
		requireThat(ConcurrencyPolicy.categoryLimit(boundaries, CampaignBuilder.counts(140, 1)), "afterFailure").
			isEqualTo(10);
		// From 295 passes and 4 failures, one trial remains below the cap of 300 even though one pass cannot certify.
		requireThat(ConcurrencyPolicy.categoryLimit(boundaries, CampaignBuilder.counts(295, 4)), "unreachable").
			isEqualTo(1);
		requireThat(ConcurrencyPolicy.categoryLimit(boundaries, CampaignBuilder.counts(296, 4)), "atCap").
			isEqualTo(0);
	}

	/**
	 * Returns outcome counts that exceed the certification cap through passes or failures.
	 *
	 * @return rows of passing and failing outcomes
	 */
	@DataProvider
	public static Object[][] countsAboveCap()
	{
		int aboveCap = Boundaries.standard(2).cap() + 1;
		return new Object[][]
			{
				{aboveCap, 0},
				{0, aboveCap}
			};
	}

	/**
	 * Verifies that a concurrency limit rejects counts exceeding the certification cap.
	 *
	 * @param passes the adopted passing outcomes
	 * @param failures the adopted failing outcomes
	 */
	@Test(dataProvider = "countsAboveCap", expectedExceptions = IllegalArgumentException.class)
	public void countsAboveCapAreRejected(int passes, int failures)
	{
		// Requirement: R-E4
		ConcurrencyPolicy.categoryLimit(Boundaries.standard(2), CampaignBuilder.counts(passes, failures));
	}

	/**
	 * Verifies that the campaign window counts the working candidate's development outcomes, and in the formal
	 * phase adds them to the attempt's outcomes when the attempt tests the same snapshot.
	 */
	@Test
	public void campaignWindowCountsSameSnapshot()
	{
		// Requirement: R-E4
		Campaign development = new CampaignBuilder().confirmed().development("formatting", 11, 0).
			slowStart(new SlowStart(16, 0)).build();
		requireThat(ConcurrencyPolicy.window(development), "development").isEqualTo(2);

		Campaign formal = new CampaignBuilder().confirmed().development("formatting", 11, 0).
			development("safety", 11, 0).slowStart(new SlowStart(16, 0)).ceiling(16).
			certificationAttempt(1).category("formatting", 12, 0).category("safety", 13, 0).build();
		// 22 development and 25 formal outcomes without failures
		requireThat(ConcurrencyPolicy.window(formal), "formal").isEqualTo(8);

		Campaign otherSnapshot = new CampaignBuilder().confirmed().development("formatting", 11, 0).
			development("safety", 11, 0).slowStart(new SlowStart(16, 0)).ceiling(16).
			certificationAttempt(1).candidate(new Candidate(3, CampaignBuilder.snapshot("other"))).
			category("formatting", 12, 0).category("safety", 13, 0).build();
		requireThat(ConcurrencyPolicy.window(otherSnapshot), "otherSnapshot").isEqualTo(4);
	}

	/**
	 * Verifies that an adopted failure resets the campaign's concurrency window to 1.
	 */
	@Test
	public void adoptedFailureResetsCampaignWindow()
	{
		// Requirement: R-E4
		Campaign campaign = new CampaignBuilder().confirmed().development("formatting", 100, 0).
			development("safety", 99, 0).slowStart(new SlowStart(8, 2)).build();
		requireThat(ConcurrencyPolicy.window(campaign), "before").isEqualTo(8);
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);
		requireThat(campaign.slowStart(), "slowStart").isEqualTo(SlowStart.INITIAL);
		requireThat(ConcurrencyPolicy.window(campaign), "after").isEqualTo(1);
		requireThat(campaign.development().count("safety").failures(), "failures").isEqualTo(1);
	}
}
