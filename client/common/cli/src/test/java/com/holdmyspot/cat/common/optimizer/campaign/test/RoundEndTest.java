package com.holdmyspot.cat.common.optimizer.campaign.test;

import static com.holdmyspot.cat.common.optimizer.campaign.test.Builder.targeted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Candidate;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.EditOrContinue;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionRun;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.CommitGate;
import com.holdmyspot.cat.common.optimizer.campaign.RegressionTestCase;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.RunFailure;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.SlowStart;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Triage;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;
import com.holdmyspot.cat.common.optimizer.statistics.ErrorBudget;
import com.holdmyspot.cat.common.secrets.PromotionSecrets;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests how a development round ends: a promotion, or a complete round without one, which always resumes the working
 * candidate's representative trials.
 */
public final class RoundEndTest
{
	/**
	 * The most representative development failures that a cell may have before an edit starts.
	 */
	private static final int FAILURE_LIMIT = EditOrContinue.failureLimit(Contract.DEFAULT_MINIMUM_PASS_RATE);

	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
	private static final Sha256 ATTEMPT = Builder.snapshot("attempt");
	/**
	 * A concurrency window that a resumed round must not keep.
	 */
	private static final SlowStart GROWN = new SlowStart(16, 0);

	/**
	 * Creates the test.
	 */
	public RoundEndTest()
	{
	}

	/**
	 * Returns a round.
	 *
	 * @param formatting the attempts used on {@code formatting}
	 * @param safety the attempts used on {@code safety}
	 * @return the round
	 */
	private static Round round(int formatting, int safety)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>();
		attempts.put("formatting", formatting);
		attempts.put("safety", safety);
		return new Round(attempts);
	}

	/**
	 * Verifies that a round is complete once every failing test used both of its prompt author attempts.
	 */
	@Test
	public void roundCompletesAfterEveryAttempt()
	{
		// Requirement: R-D6
		requireThat(round(2, 2).isComplete(), "complete").isTrue();
		requireThat(round(2, 1).isComplete(), "safetyShort").isFalse();
		requireThat(round(1, 2).isComplete(), "formattingShort").isFalse();
		requireThat(new Round(Map.of("safety", 0)).isComplete(), "fresh").isFalse();
	}

	/**
	 * Returns a campaign whose round has one prompt author attempt left, on {@code safety}.
	 *
	 * @param failures the working candidate's {@code safety} failures
	 * @return the campaign
	 */
	private static Campaign lastAttempt(int failures)
	{
		DrawnTestCase logs = targeted("safety", "logs");
		return new Builder().confirmed().working(1).development("safety", 60, failures).
			development("formatting", 60, 1).failure(logs).regression(logs, Verdict.FAIL).round(round(2, 1)).
			promptAuthorAttempts(3).slowStart(GROWN).build();
	}

	/**
	 * Rejects the last attempt of a sequential round by failing its first promotion run.
	 *
	 * @param campaign the campaign before the last attempt
	 * @return the campaign after the rejection
	 */
	private static Campaign rejectLastAttempt(Campaign campaign)
	{
		Campaign submitted = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		PromptAuthorAttempt promptAuthor = submitted.development().promptAuthor();
		return TransitionSteps.adopt(submitted, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT,
			promptAuthor.runs().getFirst().testCase(), Verdict.FAIL);
	}

	/**
	 * Verifies that a complete sequential round without a promotion keeps the working candidate and resumes its
	 * representative trials one at a time while a cell has more than the failure limit.
	 */
	@Test
	public void sequentialRoundAboveLimitResumes()
	{
		// Requirement: R-D6
		Campaign before = lastAttempt(FAILURE_LIMIT + 1);
		Campaign campaign = rejectLastAttempt(before);

		requireResumed(campaign, before);
		requireThat(campaign.choices(), "choices").isEmpty();
	}

	/**
	 * Verifies that a complete sequential round without a promotion keeps the working candidate and resumes its
	 * representative trials one at a time while every cell has at most the failure limit.
	 */
	@Test
	public void sequentialRoundWithinLimitResumes()
	{
		Campaign before = lastAttempt(FAILURE_LIMIT);
		Campaign campaign = rejectLastAttempt(before);

		requireResumed(campaign, before);
		requireThat(campaign.optimization().attempts().getLast().accepted(), "rejectedAttempt").isFalse();
	}

	/**
	 * Requires that a round ended without a pending decision and that the unchanged working candidate resumed
	 * sampling.
	 *
	 * @param campaign the campaign after the round
	 * @param before the campaign before the round's last decision
	 */
	private static void requireResumed(Campaign campaign, Campaign before)
	{
		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.development().promptAuthor(), "prompt_author").isNull();
		requireThat(campaign.development().working(), "working").isEqualTo(before.development().working());
		requireThat(campaign.development().counts(), "counts").isEqualTo(before.development().counts());
		requireThat(campaign.development().failures(), "failures").isEqualTo(before.development().failures());
		requireThat(campaign.optimization().attempts(), "retainedAttempts").isNotEmpty();
		requireThat(campaign.slowStart(), "slowStart").isEqualTo(SlowStart.INITIAL);
		List<Admission> development = Scheduler.plan(campaign, NOW, 8).stream().filter(admission ->
			admission instanceof Admission.Assign assign && assign.purpose() == Purpose.DEVELOPMENT).toList();
		requireThat(development.size(), "developmentAdmissions").isEqualTo(1);
	}

	/**
	 * Verifies that a failure after the resumed sampling goes through edit-or-continue: a third failure in a cell
	 * starts a new round.
	 */
	@Test
	public void laterFailureUsesEditOrContinue()
	{
		Campaign campaign = rejectLastAttempt(lastAttempt(FAILURE_LIMIT));
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().round().attempts(), "round").
			isEqualTo(Map.of("safety", 0, "formatting", 0));
	}

	/**
	 * Verifies that the next failure after a complete round above the failure limit starts a new round, so that each
	 * round has new failure evidence.
	 */
	@Test
	public void laterFailureAboveLimitStartsNewRound()
	{
		// Requirement: R-D6
		Campaign campaign = rejectLastAttempt(lastAttempt(FAILURE_LIMIT + 1));
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round().attempts(), "round").
			isEqualTo(Map.of("safety", 0, "formatting", 0));
	}

	/**
	 * Returns a campaign with a weak round on {@code safety} and no attempt received.
	 *
	 * @param failures the working candidate's {@code safety} failures
	 * @return the campaign
	 */
	private static Campaign weakRound(int failures)
	{
		DrawnTestCase logs = targeted("safety", "logs");
		DiagnosticRerun reproduced = new DiagnosticRerun(Verdict.FAIL, "The same defect reproduced");
		return new Builder().confirmed().working(1).development("safety", 0, failures).
			failure(new Failure(logs, "The directory was removed", List.of(), null, List.of(reproduced), List.of(),
				Builder.FILES)).
			regression(logs, Verdict.FAIL).round(new Round(Map.of("safety", 0), true, Map.of(), List.of())).
			slowStart(GROWN).build();
	}

	/**
	 * Submits the weak round's three attempts, none of which edits the working candidate.
	 *
	 * @param campaign the campaign before the attempts
	 * @return the campaign after the round
	 */
	private static Campaign submitUnacceptedAttempts(Campaign campaign)
	{
		Sha256 working = campaign.development().working().snapshot();
		Campaign updated = campaign;
		for (int attempt = 0; attempt < 3; ++attempt)
			updated = TransitionSteps.submit(updated, "safety", working, List.of());
		return updated;
	}

	/**
	 * Verifies that a weak round in which no attempt was accepted resumes sampling while every cell has at most the
	 * failure limit.
	 */
	@Test
	public void weakRoundWithinLimitResumes()
	{
		Campaign before = weakRound(FAILURE_LIMIT);
		Campaign campaign = submitUnacceptedAttempts(before);

		requireResumed(campaign, before);
		requireThat(campaign.optimization().attempts().size(), "retainedAttempts").isEqualTo(3);
	}

	/**
	 * Verifies that a weak round in which no attempt was accepted resumes sampling while a cell has more than the
	 * failure limit.
	 */
	@Test
	public void weakRoundAboveLimitResumes()
	{
		Campaign before = weakRound(FAILURE_LIMIT + 1);
		Campaign campaign = submitUnacceptedAttempts(before);

		requireResumed(campaign, before);
		requireThat(campaign.optimization().attempts().size(), "retainedAttempts").isEqualTo(3);
	}

	/**
	 * Returns a campaign whose round, triggered by the working candidate's reproducible failure on {@code safety}, has
	 * one prompt author attempt left. Its {@code safety} and {@code plain_list} counts meet the commit gate and
	 * {@code formatting} is one passing trial short of it.
	 *
	 * @return the campaign
	 */
	private static Campaign oneTrialShortOfCommitGate()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		return new Builder().confirmed().working(1).readyBaseline().development("safety", 99, List.of(1, 0)).
			development("formatting", 99, 0).development("plain_list", 100, 0).failure(logs).
			regression(logs, Verdict.FAIL).
			round(new Round(Map.of("safety", 1))).promptAuthorAttempts(1).build();
	}

	/**
	 * Verifies that a working candidate that resumed sampling after a round without a promotion is committed once its
	 * counts meet the commit gate, although no later trial ran the round's triggering failure again: the round's end
	 * settled the working candidate's failing verdict on that test case.
	 */
	@Test
	public void resumedCandidateCommitsAtCommitGate()
	{
		Campaign campaign = rejectLastAttempt(oneTrialShortOfCommitGate());
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(CommitGate.isCommitDue(campaign), "dueBeforeTrials").isFalse();

		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		requireThat(CommitGate.isCommitDue(campaign), "dueAfterTrials").isTrue();
		RegressionTestCase regression = campaign.lineage().regressionSet().getFirst();
		requireThat(regression.working(), "workingVerdict").isEqualTo(Verdict.FAIL);
		requireThat(regression.settled(), "settled").isTrue();

		Candidate working = campaign.development().working();
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(new CapCalibration(
			ErrorBudget.certificationLevel(1), Contract.DEFAULT_MINIMUM_PASS_RATE, 262), 1, Builder.CONTROL));
		requireThat(campaign.runningAttempt().orElseThrow().candidate(), "committed").isEqualTo(working);
	}

	/**
	 * Verifies that a later representative failure of the resumed working candidate on a settled regression test case
	 * replaces the settled failure, blocks the commit gate again and goes through edit-or-continue.
	 */
	@Test
	public void laterFailureOnSettledTestCaseBlocksGate()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		Campaign campaign = rejectLastAttempt(oneTrialShortOfCommitGate());
		requireThat(campaign.lineage().regressionSet().getFirst().settled(), "settledBefore").isTrue();

		boolean failed = false;
		for (int trial = 0; trial < CommitGate.MINIMUM_TRIALS && !failed; ++trial)
		{
			campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
			DrawnTestCase drawn = campaign.trials().getLast().testCase();
			failed = PromotionSecrets.access().isSameTestCase(drawn, logs);
			campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), failOnlyContent(drawn, logs));
		}
		requireThat(failed, "drewSettledTestCase").isTrue();

		RegressionTestCase regression = campaign.lineage().regressionSet().getFirst();
		requireThat(regression.working(), "workingVerdict").isEqualTo(Verdict.FAIL);
		requireThat(regression.settled(), "settled").isFalse();
		requireThat(CommitGate.isMet(campaign), "met").isFalse();
		requireThat(campaign.development().diagnosis().pending().size(), "diagnosed").isEqualTo(1);
	}

	/**
	 * Verifies that a promotion after a round settled the working candidate's failure does not settle the promoted
	 * candidate's failure on the same test case: the promoted attempt fixes a later blocking failure but still fails the
	 * settled test case, and that failure blocks the commit gate for the promoted candidate.
	 */
	@Test
	public void promotionDoesNotCarrySettledState()
	{
		DrawnTestCase logs = targeted("safety", "logs");
		Campaign campaign = rejectLastAttempt(oneTrialShortOfCommitGate());
		requireThat(campaign.lineage().regressionSet().getFirst().settled(), "settledBefore").isTrue();
		campaign = TransitionSteps.fail(campaign, "safety", new Triage(Impact.BLOCKING, "The directory was removed."));
		requireThat(PromotionSecrets.access().isSameTestCase(campaign.development().failures().getLast().testCase(), logs),
			"blockingFailureOnSettledTestCase").isFalse();

		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		while (campaign.development().promptAuthor().runs().stream().anyMatch(run -> run.verdict() == null))
		{
			PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
			PromotionRun run = promptAuthor.runs().stream().filter(element -> element.verdict() == null).
				findFirst().orElseThrow();
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT,
				run.testCase(), failOnlyContent(run.testCase(), logs));
		}
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(ATTEMPT));

		requireThat(campaign.development().working().snapshot(), "working").isEqualTo(ATTEMPT);
		RegressionTestCase regression = campaign.lineage().regressionSet().getFirst();
		requireThat(regression.working(), "promotedVerdict").isEqualTo(Verdict.FAIL);
		requireThat(regression.settled(), "settled").isFalse();
	}

	/**
	 * Returns the judged outcome of a trial that fails only on one test case's content.
	 *
	 * @param ran the test case that the trial ran
	 * @param failing the test case whose content fails
	 * @return {@code FAIL} if {@code ran} has the content of {@code failing}, otherwise {@code PASS}
	 */
	private static Verdict failOnlyContent(DrawnTestCase ran, DrawnTestCase failing)
	{
		if (PromotionSecrets.access().isSameTestCase(ran, failing))
			return Verdict.FAIL;
		return Verdict.PASS;
	}

	/**
	 * Verifies that an infrastructure failure or an interruption of a prompt author attempt's run never completes the
	 * round.
	 */
	@Test
	public void interruptionNeverCompletesRound()
	{
		// Requirement: R-D6
		Campaign campaign = TransitionSteps.submit(lastAttempt(FAILURE_LIMIT + 1), "safety", ATTEMPT,
			List.of());
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT,
			promptAuthor.runs().getFirst().testCase());
		PromptAuthorAttempt assigned = campaign.development().promptAuthor();
		Round beforeInterruption = campaign.development().round();
		TrialKey key = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INFRASTRUCTURE,
			"provider error", NOW));
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INTERRUPTED,
			"slice ended", NOW));

		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(beforeInterruption);
		requireThat(campaign.development().promptAuthor(), "prompt_author").isEqualTo(assigned);
	}

	/**
	 * Verifies that a promotion ends the round without a pending decision.
	 */
	// Requirement: R-D3
	@Test
	public void promotionEndsRound()
	{
		// Requirement: R-D6
		Campaign campaign = TransitionSteps.submit(lastAttempt(FAILURE_LIMIT + 1), "safety", ATTEMPT,
			List.of());
		while (campaign.development().promptAuthor().runs().stream().anyMatch(run -> run.verdict() == null))
		{
			PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
			PromotionRun run = promptAuthor.runs().stream().filter(element -> element.verdict() == null).
				findFirst().orElseThrow();
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), ATTEMPT,
				run.testCase(), Verdict.PASS);
		}
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(ATTEMPT));
		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.development().working().snapshot(), "working").isEqualTo(ATTEMPT);
	}

	/**
	 * Verifies that each test gets at most 2 prompt author attempts per round.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void cellGetsTwoAttempts()
	{
		// Requirement: R-D6
		TransitionSteps.submit(lastAttempt(1), "formatting", ATTEMPT, List.of());
	}
}
