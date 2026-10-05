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
 * Tests {@link Plateau} and the development rounds that lead to it.
 */
public final class PlateauTest
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
	public PlateauTest()
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
		requireThat(Plateau.isComplete(round(2, 2)), "complete").isTrue();
		requireThat(Plateau.isComplete(round(2, 1)), "safetyShort").isFalse();
		requireThat(Plateau.isComplete(round(1, 2)), "formattingShort").isFalse();
		requireThat(Plateau.isComplete(new Round(Map.of("safety", 0))), "fresh").isFalse();
	}

	/**
	 * Verifies that a plateau requires a complete round while a cell has more than the failure limit.
	 */
	@Test
	public void plateauRequiresFailuresAboveLimit()
	{
		Map<String, CellCounts> atLimit = Map.of("safety", Builder.counts(60, EditOrContinue.FAILURE_LIMIT),
			"formatting", Builder.counts(60, 1));
		Map<String, CellCounts> aboveLimit = Map.of("safety", Builder.counts(60, EditOrContinue.FAILURE_LIMIT + 1),
			"formatting", Builder.counts(60, 0));
		requireThat(Plateau.isReached(round(2, 2), atLimit), "atLimit").isFalse();
		requireThat(Plateau.isReached(round(2, 2), aboveLimit), "aboveLimit").isTrue();
		requireThat(Plateau.isReached(round(2, 1), aboveLimit), "incomplete").isFalse();
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
	 * Verifies that a complete sequential round without a promotion establishes a plateau while a cell has more than
	 * the failure limit.
	 */
	@Test
	public void completeRoundAboveLimitIsPlateau()
	{
		// Requirement: R-D6
		Campaign campaign = rejectLastAttempt(lastAttempt(EditOrContinue.FAILURE_LIMIT + 1));

		requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.PLATEAU);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.PlateauReached());
		requireThat(campaign.choices(), "choices").
			containsExactly(List.of(Choice.ACCEPT_WITHOUT_CERTIFICATION, Choice.STOP));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
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
	 * Requires that a round ended without a plateau and that the unchanged working candidate resumed sampling.
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
			failure(new Failure(logs, "The directory was removed", List.of(), null, List.of(reproduced), List.of())).
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
	 * Verifies that a weak round in which no attempt was accepted establishes a plateau while a cell has more than the
	 * failure limit.
	 */
	@Test
	public void weakRoundAboveLimitIsPlateau()
	{
		Campaign campaign = submitUnacceptedAttempts(weakRound(EditOrContinue.FAILURE_LIMIT + 1));

		requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.PLATEAU);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.PlateauReached());
		requireThat(campaign.development().round(), "round").isNotNull();
	}

	/**
	 * Verifies that a working candidate that resumed sampling after a round without a promotion is committed once it
	 * meets the readiness gate. Its later representative trials include a pass on the content of the round's triggering
	 * failure, which replaces the failing verdict in the regression set.
	 */
	@Test
	public void resumedCandidateCommitsAtReadiness()
	{
		TrialInstance logs = targeted("safety", "logs");
		Campaign campaign = new Builder().confirmed().working(1).readyBaseline().
			development("safety", 98, List.of(1, 0)).development("formatting", 100, 0).failure(logs).
			regression(logs, Verdict.FAIL).round(new Round(Map.of("safety", 1))).authorAttempts(1).build();
		campaign = rejectLastAttempt(campaign);
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(ReadinessGate.isCommitDue(campaign), "dueBeforeTrials").isFalse();

		for (int trial = 0; trial < ReadinessGate.MINIMUM_TRIALS && !ReadinessGate.isCommitDue(campaign); ++trial)
			campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.PASS);
		requireThat(ReadinessGate.isCommitDue(campaign), "dueAfterTrials").isTrue();
		requireThat(campaign.lineage().regressionSet().getFirst().working(), "laterVerdict").isEqualTo(Verdict.PASS);

		Candidate working = campaign.development().working();
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(new CapCalibration(
			ErrorBudget.certificationLevel(1), 262), 1));
		requireThat(campaign.runningAttempt().orElseThrow().candidate(), "committed").isEqualTo(working);
	}

	/**
	 * Verifies that an infrastructure failure or an interruption of an author attempt's run never completes the
	 * round.
	 */
	@Test
	public void interruptionNeverEstablishesPlateau()
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
	 * Verifies that a promotion ends the round without a plateau.
	 */
	// Requirement: R-D3
	@Test
	public void promotionEndsRoundWithoutPlateau()
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
