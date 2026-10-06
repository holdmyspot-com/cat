package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.Builder.targeted;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

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
	 * Verifies that a round is complete once every failing category used both of its author attempts.
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
	 * Returns a campaign whose round has one author attempt left, on {@code safety}.
	 *
	 * @param failures the working candidate's {@code safety} failures
	 * @return the campaign
	 */
	private static Campaign lastAttempt(int failures)
	{
		TrialInstance logs = targeted("safety", "logs");
		return new Builder().confirmed().working(1).development("safety", 60, failures).
			development("formatting", 60, 1).failure(logs).regression(logs, Verdict.FAIL).round(round(2, 1)).
			authorAttempts(3).slowStart(GROWN).build();
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
		AuthorAttempt author = submitted.development().author();
		return TransitionSteps.adopt(submitted, Purpose.PROMOTION, author.number(), ATTEMPT,
			author.runs().getFirst().instance(), Verdict.FAIL);
	}

	/**
	 * Verifies that a complete sequential round without a promotion keeps the working candidate and resumes its
	 * representative trials one at a time while a cell has more than the failure limit.
	 */
	@Test
	public void sequentialRoundAboveLimitResumes()
	{
		// Requirement: R-D6
		Campaign before = lastAttempt(EditOrContinue.FAILURE_LIMIT + 1);
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
		Campaign before = lastAttempt(EditOrContinue.FAILURE_LIMIT);
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
		requireThat(campaign.development().author(), "author").isNull();
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
		Campaign campaign = rejectLastAttempt(lastAttempt(EditOrContinue.FAILURE_LIMIT));
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
		Campaign campaign = rejectLastAttempt(lastAttempt(EditOrContinue.FAILURE_LIMIT + 1));
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
		TrialInstance logs = targeted("safety", "logs");
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
		Campaign before = weakRound(EditOrContinue.FAILURE_LIMIT);
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
		Campaign before = weakRound(EditOrContinue.FAILURE_LIMIT + 1);
		Campaign campaign = submitUnacceptedAttempts(before);

		requireResumed(campaign, before);
		requireThat(campaign.optimization().attempts().size(), "retainedAttempts").isEqualTo(3);
	}

	/**
	 * Returns a campaign whose round, triggered by the working candidate's reproducible failure on {@code safety}, has
	 * one author attempt left. Its {@code safety} counts meet the readiness gate and {@code formatting} is one passing
	 * trial short of it.
	 *
	 * @return the campaign
	 */
	private static Campaign oneTrialShortOfReadiness()
	{
		TrialInstance logs = targeted("safety", "logs");
		return new Builder().confirmed().working(1).readyBaseline().development("safety", 99, List.of(1, 0)).
			development("formatting", 99, 0).failure(logs).regression(logs, Verdict.FAIL).
			round(new Round(Map.of("safety", 1))).authorAttempts(1).build();
	}

	/**
	 * Verifies that a working candidate that resumed sampling after a round without a promotion is committed once its
	 * counts meet the readiness gate, although no later trial ran the round's triggering failure again: the round's end
	 * settled the working candidate's failing verdict on that instance.
	 */
	@Test
	public void resumedCandidateCommitsAtReadiness()
	{
		Campaign campaign = rejectLastAttempt(oneTrialShortOfReadiness());
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(ReadinessGate.isCommitDue(campaign), "dueBeforeTrials").isFalse();

		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		requireThat(ReadinessGate.isCommitDue(campaign), "dueAfterTrials").isTrue();
		RegressionInstance regression = campaign.lineage().regressionSet().getFirst();
		requireThat(regression.working(), "workingVerdict").isEqualTo(Verdict.FAIL);
		requireThat(regression.settled(), "settled").isTrue();

		Candidate working = campaign.development().working();
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(new CapCalibration(
			ErrorBudget.certificationLevel(1), 262), 1));
		requireThat(campaign.runningAttempt().orElseThrow().candidate(), "committed").isEqualTo(working);
	}

	/**
	 * Verifies that a later representative failure of the resumed working candidate on a settled regression instance
	 * replaces the settled failure, blocks the readiness gate again and goes through edit-or-continue.
	 */
	@Test
	public void laterFailureOnSettledInstanceBlocksGate()
	{
		TrialInstance logs = targeted("safety", "logs");
		Campaign campaign = rejectLastAttempt(oneTrialShortOfReadiness());
		requireThat(campaign.lineage().regressionSet().getFirst().settled(), "settledBefore").isTrue();

		boolean failed = false;
		for (int trial = 0; trial < ReadinessGate.MINIMUM_TRIALS && !failed; ++trial)
		{
			campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
			TrialInstance drawn = campaign.trials().getLast().instance();
			failed = Promotion.isSameContent(drawn, logs);
			campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), failOnlyContent(drawn, logs));
		}
		requireThat(failed, "drewSettledInstance").isTrue();

		RegressionInstance regression = campaign.lineage().regressionSet().getFirst();
		requireThat(regression.working(), "workingVerdict").isEqualTo(Verdict.FAIL);
		requireThat(regression.settled(), "settled").isFalse();
		requireThat(ReadinessGate.isMet(campaign), "met").isFalse();
		requireThat(campaign.development().diagnosis().pending().size(), "diagnosed").isEqualTo(1);
	}

	/**
	 * Verifies that a promotion after a round settled the working candidate's failure does not settle the promoted
	 * candidate's failure on the same instance: the promoted attempt fixes a later blocking failure but still fails the
	 * settled instance, and that failure blocks the readiness gate for the promoted candidate.
	 */
	@Test
	public void promotionDoesNotCarrySettledState()
	{
		TrialInstance logs = targeted("safety", "logs");
		Campaign campaign = rejectLastAttempt(oneTrialShortOfReadiness());
		requireThat(campaign.lineage().regressionSet().getFirst().settled(), "settledBefore").isTrue();
		campaign = TransitionSteps.fail(campaign, "safety", new Triage(Impact.BLOCKING, "The directory was removed."));
		requireThat(Promotion.isSameContent(campaign.development().failures().getLast().instance(), logs),
			"blockingFailureOnSettledInstance").isFalse();

		campaign = TransitionSteps.submit(campaign, "safety", ATTEMPT, List.of());
		while (campaign.development().author().runs().stream().anyMatch(run -> run.verdict() == null))
		{
			AuthorAttempt author = campaign.development().author();
			PromotionRun run = author.runs().stream().filter(element -> element.verdict() == null).
				findFirst().orElseThrow();
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, run.instance(),
				failOnlyContent(run.instance(), logs));
		}
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(ATTEMPT));

		requireThat(campaign.development().working().snapshot(), "working").isEqualTo(ATTEMPT);
		RegressionInstance regression = campaign.lineage().regressionSet().getFirst();
		requireThat(regression.working(), "promotedVerdict").isEqualTo(Verdict.FAIL);
		requireThat(regression.settled(), "settled").isFalse();
	}

	/**
	 * Returns the judged outcome of a trial that fails only on one instance's content.
	 *
	 * @param ran the instance that the trial ran
	 * @param failing the instance whose content fails
	 * @return {@code FAIL} if {@code ran} has the content of {@code failing}, otherwise {@code PASS}
	 */
	private static Verdict failOnlyContent(TrialInstance ran, TrialInstance failing)
	{
		if (Promotion.isSameContent(ran, failing))
			return Verdict.FAIL;
		return Verdict.PASS;
	}

	/**
	 * Verifies that an infrastructure failure or an interruption of an author attempt's run never completes the
	 * round.
	 */
	@Test
	public void interruptionNeverCompletesRound()
	{
		// Requirement: R-D6
		Campaign campaign = TransitionSteps.submit(lastAttempt(EditOrContinue.FAILURE_LIMIT + 1), "safety", ATTEMPT,
			List.of());
		AuthorAttempt author = campaign.development().author();
		campaign = TransitionSteps.assign(campaign, Purpose.PROMOTION, author.number(), ATTEMPT,
			author.runs().getFirst().instance());
		AuthorAttempt assigned = campaign.development().author();
		Round beforeInterruption = campaign.development().round();
		TrialKey key = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INFRASTRUCTURE,
			"provider error", NOW, null));
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = Transitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INTERRUPTED,
			"slice ended", NOW, null));

		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(beforeInterruption);
		requireThat(campaign.development().author(), "author").isEqualTo(assigned);
	}

	/**
	 * Verifies that a promotion ends the round without a pending decision.
	 */
	// Requirement: R-D3
	@Test
	public void promotionEndsRound()
	{
		// Requirement: R-D6
		Campaign campaign = TransitionSteps.submit(lastAttempt(EditOrContinue.FAILURE_LIMIT + 1), "safety", ATTEMPT,
			List.of());
		while (campaign.development().author().runs().stream().anyMatch(run -> run.verdict() == null))
		{
			AuthorAttempt author = campaign.development().author();
			PromotionRun run = author.runs().stream().filter(element -> element.verdict() == null).
				findFirst().orElseThrow();
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), ATTEMPT, run.instance(),
				Verdict.PASS);
		}
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(ATTEMPT));
		requireThat(campaign.outcome(), "outcome").isNull();
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.development().working().snapshot(), "working").isEqualTo(ATTEMPT);
	}

	/**
	 * Verifies that each category gets at most 2 author attempts per round.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void cellGetsTwoAttempts()
	{
		// Requirement: R-D6
		TransitionSteps.submit(lastAttempt(1), "formatting", ATTEMPT, List.of());
	}
}
