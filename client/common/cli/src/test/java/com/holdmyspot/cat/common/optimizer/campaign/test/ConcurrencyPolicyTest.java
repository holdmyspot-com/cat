package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.ConcurrencyPolicy;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.RunFailure;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Triage;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.WorkUnit;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests how many trials run at once: each unit of work starts at its spare failures plus one, grows after full
 * windows of passes, drops at a failure that consumes a spare failure, and halves after a run without an outcome.
 */
public final class ConcurrencyPolicyTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
	private static final Sha256 ATTEMPT = Builder.snapshot("attempt");
	/**
	 * The minimum-pass-rate part of the {@code safety} cell of the first certification attempt.
	 */
	private static final WorkUnit SAFETY = WorkUnit.formal(1, "safety", false);

	/**
	 * Creates the test.
	 */
	public ConcurrencyPolicyTest()
	{
	}

	/**
	 * Adopts passing development trials in {@code formatting}.
	 *
	 * @param campaign the campaign
	 * @param passes the number of passing trials
	 * @return the updated campaign
	 */
	private static Campaign pass(Campaign campaign, int passes)
	{
		Campaign updated = campaign;
		for (int i = 0; i < passes; ++i)
			updated = TransitionSteps.adopt(updated, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		return updated;
	}

	/**
	 * Returns a confirmed campaign that samples its working candidate in {@code formatting} and {@code safety}.
	 *
	 * @return the builder
	 */
	private static Builder developing()
	{
		return new Builder().confirmed().development("formatting", 60, 0).development("safety", 60, 0);
	}

	/**
	 * Verifies that a working candidate starts at one trial at a time and reaches 8 after 7 straight passes.
	 */
	@Test
	public void developmentWindowStartsAtOneAndGrows()
	{
		// Requirement: R-E4
		Campaign campaign = developing().build();
		requireThat(ConcurrencyPolicy.window(campaign), "start").isEqualTo(1);
		campaign = pass(campaign, 6);
		requireThat(ConcurrencyPolicy.window(campaign), "afterSixPasses").isEqualTo(4);
		campaign = pass(campaign, 1);
		requireThat(ConcurrencyPolicy.window(campaign), "afterSevenPasses").isEqualTo(8);
	}

	/**
	 * Verifies that a self-recovering development failure leaves the window unchanged.
	 */
	@Test
	public void selfRecoveringFailureKeepsWindow()
	{
		// Requirement: R-E4
		Campaign campaign = pass(developing().build(), 7);
		campaign = TransitionSteps.fail(campaign, "safety",
			new Triage(Impact.SELF_RECOVERING, "The reply re-read a file it had already read."));
		requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(8);
	}

	/**
	 * Verifies that a blocking development failure returns the window to 1, and that 7 straight passes afterwards
	 * reach 8 again.
	 */
	@Test
	public void blockingFailureReturnsWindowToOne()
	{
		// Requirement: R-E4
		Campaign campaign = pass(developing().build(), 7);
		campaign = TransitionSteps.fail(campaign, "safety",
			new Triage(Impact.BLOCKING, "The reply deleted the logs and stopped."));
		requireThat(ConcurrencyPolicy.window(campaign), "afterBlocking").isEqualTo(1);
		campaign = pass(campaign, 7);
		requireThat(ConcurrencyPolicy.window(campaign), "afterSevenPasses").isEqualTo(8);
	}

	/**
	 * Returns the run failures that halve the window.
	 *
	 * @return rows of run failures
	 */
	@DataProvider
	public static Object[][] infrastructureAttempts()
	{
		return new Object[][]
			{
				{RunFailure.INFRASTRUCTURE},
				{RunFailure.RATE_LIMITED}
			};
	}

	/**
	 * Verifies that a run without an outcome halves the window, and that a full window of trials completed with an
	 * outcome doubles it again.
	 *
	 * @param failure why the run ended without an outcome
	 */
	@Test(dataProvider = "infrastructureAttempts")
	public void infrastructureAttemptHalvesWindow(RunFailure failure)
	{
		// Requirement: R-E4
		Campaign campaign = pass(developing().build(), 7);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey key = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, failure, "provider error", NOW));
		requireThat(ConcurrencyPolicy.window(campaign), "halved").isEqualTo(4);

		campaign = TransitionSteps.run(campaign, key, Verdict.PASS);
		campaign = pass(campaign, 2);
		requireThat(ConcurrencyPolicy.window(campaign), "partOfWindow").isEqualTo(4);
		campaign = pass(campaign, 1);
		requireThat(ConcurrencyPolicy.window(campaign), "restored").isEqualTo(8);
	}

	/**
	 * Verifies that a run that CAT interrupted leaves the window unchanged.
	 */
	@Test
	public void interruptedRunKeepsWindow()
	{
		// Requirement: R-E4
		Campaign campaign = pass(developing().build(), 7);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey key = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INTERRUPTED, "stopped", NOW));
		requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(8);
	}

	/**
	 * Verifies that the campaign ceiling and the throttle limit still bound the window.
	 */
	@Test
	public void ceilingAndThrottleBoundWindow()
	{
		// Requirement: R-E4
		Campaign grown = pass(developing().ceiling(16).build(), 15);
		requireThat(ConcurrencyPolicy.window(grown), "grown").isEqualTo(16);
		Campaign ceiling = pass(developing().ceiling(3).build(), 7);
		requireThat(ConcurrencyPolicy.window(ceiling), "ceiling").isEqualTo(3);
		Campaign throttled = pass(developing().throttle(new Throttle(Instant.EPOCH, 2, 0, 2)).build(), 7);
		requireThat(ConcurrencyPolicy.window(throttled), "throttle").isEqualTo(2);
	}

	/**
	 * Returns a builder of a running attempt in which {@code formatting} and {@code plain_list} certified.
	 *
	 * @return the builder
	 */
	private static Builder.AttemptBuilder certifying()
	{
		return new Builder().confirmed().working(1).readyBaseline().development("formatting", 200, 0).
			development("plain_list", 200, 0).development("safety", 200, 0).certificationAttempt(1).
			cell("formatting", 100, 0).cell("plain_list", 100, 0);
	}

	/**
	 * Returns the formal trials of a cell in the campaign's next plan.
	 *
	 * @param campaign the campaign
	 * @param cell the cell's identifier
	 * @return the number of new formal trials planned in the cell
	 */
	private static long formalAdmissions(Campaign campaign, String cell)
	{
		return Scheduler.plan(campaign, NOW, Campaign.MAXIMUM_CEILING).stream().filter(admission ->
			admission instanceof Admission.Assign assign && assign.purpose() == Purpose.FORMAL &&
				assign.cell().equals(cell)).count();
	}

	/**
	 * Verifies that a formal cell starts at its spare failures plus one: a standard cell decides out of reach at its
	 * fourth failure, so it absorbs 3.
	 */
	@Test
	public void formalCellStartsAtSpareFailuresPlusOne()
	{
		// Requirement: R-E4
		Campaign campaign = certifying().cell("safety", 50, 0).campaign().build();
		requireThat(formalAdmissions(campaign, "safety"), "start").isEqualTo(4L);
	}

	/**
	 * Verifies that a formal cell that can absorb 3 more failures before a stopping decision runs at most 4 trials
	 * at once after a failure.
	 */
	@Test
	public void formalFailureLimitsWindowToSpareFailures()
	{
		// Requirement: R-E4
		Campaign campaign = certifying().boundaries(Boundaries.reachableAtFourFailures(Boundaries.standard(3), 10)).
			cell("safety", 50, 0).campaign().window(SAFETY, 8).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.FORMAL, "safety", Verdict.FAIL);
		requireThat(formalAdmissions(campaign, "safety"), "afterFailure").isEqualTo(4L);
	}

	/**
	 * Verifies that a formal cell that one more failure would decide runs one trial at a time.
	 */
	@Test
	public void formalOneFailureFromDecisionRunsAlone()
	{
		// Requirement: R-E4
		Campaign campaign = certifying().cell("safety", 50, 2).campaign().window(SAFETY, 8).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.FORMAL, "safety", Verdict.FAIL);
		requireThat(formalAdmissions(campaign, "safety"), "afterFailure").isEqualTo(1L);
	}

	/**
	 * Verifies that the cell limit still bounds a formal cell's window.
	 */
	@Test
	public void cellLimitBoundsFormalWindow()
	{
		// Requirement: R-E4
		Campaign campaign = certifying().cell("safety", 99, 0).campaign().window(SAFETY, 8).build();
		requireThat(formalAdmissions(campaign, "safety"), "window").isEqualTo(1L);
	}

	/**
	 * Returns a campaign whose prompt author attempt addresses failing test cases in {@code safety}, before any of its
	 * promotion runs.
	 *
	 * @param testCases the attempt's triggering test cases
	 * @return the campaign
	 */
	private static Campaign promoting(DrawnTestCase... testCases)
	{
		Builder builder = new Builder().confirmed().working(1).readyBaseline();
		for (DrawnTestCase testCase : testCases)
			builder.failure(testCase);
		Campaign campaign = builder.round(new Round(Map.of("safety", 0))).build();
		return TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
	}

	/**
	 * Returns the promotion runs in the campaign's next plan.
	 *
	 * @param campaign the campaign
	 * @return the number of new promotion runs planned
	 */
	private static long promotionAdmissions(Campaign campaign)
	{
		return Scheduler.plan(campaign, NOW, Campaign.MAXIMUM_CEILING).stream().filter(admission ->
			admission instanceof Admission.Assign assign && assign.purpose() == Purpose.PROMOTION).count();
	}

	/**
	 * Verifies that a prompt author attempt runs as many promotion runs at once as it has triggering test cases that
	 * it can still fix, and that a failing triggering run lowers the window to the test cases still open.
	 */
	@Test
	public void promotionWindowFollowsFixableTestCases()
	{
		// Requirement: R-E4
		DrawnTestCase logs = Builder.targeted("safety", "logs");
		DrawnTestCase cache = Builder.targeted("safety", "cache");
		requireThat(promotionAdmissions(promoting(logs)), "oneTestCase").isEqualTo(1L);
		Campaign campaign = promoting(logs, cache);
		requireThat(promotionAdmissions(campaign), "twoTestCases").isEqualTo(2L);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, ATTEMPT, logs, Verdict.FAIL);
		requireThat(promotionAdmissions(campaign), "afterTriggeringFailure").isEqualTo(1L);
	}

	/**
	 * Verifies that the test limit equals the passes still required to certify, and is at least 1 below the
	 * cap and 0 at the cap.
	 */
	@Test
	public void cellLimitFollowsRequiredPasses()
	{
		// Requirement: R-E4
		DecisionBoundaries boundaries = Boundaries.standard(2);
		requireThat(ConcurrencyPolicy.cellLimit(boundaries, Builder.counts(0, 0)), "empty").
			isEqualTo(100);
		requireThat(ConcurrencyPolicy.cellLimit(boundaries, Builder.counts(97, 0)), "threeShort").
			isEqualTo(3);
		requireThat(ConcurrencyPolicy.cellLimit(boundaries, Builder.counts(99, 0)), "oneShort").
			isEqualTo(1);
		requireThat(ConcurrencyPolicy.cellLimit(boundaries, Builder.counts(140, 1)), "afterFailure").
			isEqualTo(10);
		// From 295 passes and 4 failures, one trial remains below the cap of 300 even though one pass cannot certify.
		requireThat(ConcurrencyPolicy.cellLimit(boundaries, Builder.counts(295, 4)), "unreachable").
			isEqualTo(1);
		requireThat(ConcurrencyPolicy.cellLimit(boundaries, Builder.counts(296, 4)), "atCap").
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
		ConcurrencyPolicy.cellLimit(Boundaries.standard(2), Builder.counts(passes, failures));
	}
}
