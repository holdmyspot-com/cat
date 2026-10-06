package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

/**
 * Exercises withdrawal of promoted candidates through representative-outcome transitions.
 */
public final class RevertTest
{
	/**
	 * Creates the test.
	 */
	public RevertTest()
	{
	}

	/**
	 * A worse promoted candidate restores the predecessor's retained evidence and completes an unproductive round,
	 * after which the predecessor resumes sampling because every cell has at most the failure limit. The round's end
	 * settles the predecessor's restored failing verdict.
	 */
	// Requirement: R-D15
	@Test
	public void restoresPredecessorAndResumesSampling()
	{
		Campaign predecessor = predecessor(99, 1, Map.of("safety", 1));
		Campaign promoted = promote(predecessor);
		Campaign reverted = TransitionSteps.adopt(promoted, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(reverted.development().working(), "working").isEqualTo(predecessor.development().working());
		requireThat(reverted.development().counts(), "counts").isEqualTo(predecessor.development().counts());
		requireThat(reverted.development().failures(), "failures").isEqualTo(predecessor.development().failures());
		RegressionInstance retained = predecessor.lineage().regressionSet().getFirst();
		requireThat(reverted.lineage().regressionSet().getFirst(), "retainedRegression").
			isEqualTo(new RegressionInstance(retained.instance(), retained.working(), true));
		requireThat(reverted.development().reverts().getFirst().withdrawn(), "withdrawn").
			isEqualTo(promoted.development().working());
		requireThat(reverted.development().reverts().getFirst().development().count("safety"), "withdrawnCounts").
			isEqualTo(Builder.counts(0, 1));
		requireThat(reverted.development().reverts().getFirst().development().failures().size(), "withdrawnFailures").
			isEqualTo(1);
		requireThat(reverted.outcome(), "outcome").isNull();
		requireThat(reverted.pending(), "pendingDecision").isNull();
		requireThat(reverted.development().round(), "round").isNull();
		requireThat(reverted.slowStart(), "slowStart").isEqualTo(SlowStart.INITIAL);
		requireThat(Scheduler.plan(reverted, Instant.parse("2026-10-01T12:00:00Z"), 8).stream().
			filter(admission -> admission instanceof Admission.Assign assign &&
				assign.purpose() == Purpose.DEVELOPMENT).count(), "developmentAdmissions").isEqualTo(1L);
	}

	/**
	 * A revert that completes an unproductive round resumes the restored predecessor's representative trials even
	 * while it has more than the failure limit in a cell.
	 */
	@Test
	public void resumesRestoredPredecessorAboveLimit()
	{
		Campaign predecessor = predecessor(97, EditOrContinue.FAILURE_LIMIT + 1, Map.of("safety", 1));
		Campaign reverted = TransitionSteps.adopt(promote(predecessor), Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(reverted.development().working(), "working").isEqualTo(predecessor.development().working());
		requireThat(reverted.development().counts(), "counts").isEqualTo(predecessor.development().counts());
		requireThat(reverted.outcome(), "outcome").isNull();
		requireThat(reverted.pending(), "pendingDecision").isNull();
		requireThat(reverted.development().round(), "round").isNull();
		requireThat(reverted.slowStart(), "slowStart").isEqualTo(SlowStart.INITIAL);
	}

	/**
	 * A blocking failure that makes the promoted candidate worse than its predecessor restores the predecessor and its
	 * round instead of starting a round for the withdrawn candidate. The restored round is complete, so it ends.
	 */
	@Test
	public void blockingFailureRestoresPredecessor()
	{
		// Requirement: R-D16
		Campaign predecessor = predecessor(99, 1, Map.of("safety", 1));
		Campaign promoted = promote(predecessor);
		Campaign reverted = TransitionSteps.fail(promoted, "safety", new Triage(Impact.BLOCKING,
			"The reply deleted the logs and stopped."));

		requireThat(reverted.development().working(), "working").isEqualTo(predecessor.development().working());
		requireThat(reverted.development().counts(), "counts").isEqualTo(predecessor.development().counts());
		requireThat(reverted.development().round(), "round").isNull();
		requireThat(reverted.outcome(), "outcome").isNull();
	}

	/**
	 * A bound at or above the predecessor's rate leaves the promoted candidate working.
	 */
	// Requirement: R-D15
	@Test
	public void keepsCandidateWhoseBoundStaysAboveRate()
	{
		Campaign promoted = promote(predecessor(13, 7, Map.of("safety", 1)));
		Campaign campaign = TransitionSteps.adopt(promoted, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(campaign.development().working(), "working").isEqualTo(promoted.development().working());
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(Builder.counts(0, 2));
		requireThat(campaign.pending(), "pendingDecision").isNull();
	}

	/**
	 * A Wilson upper bound equal to the predecessor's perfect pass rate leaves the candidate working.
	 */
	// Requirement: R-D15
	@Test
	public void keepsCandidateAtEqualBound()
	{
		Campaign promoted = promote(predecessor(1, 0, Map.of("safety", 1)));
		Campaign campaign = promoted;
		for (int outcome = 0; outcome < 10; ++outcome)
			campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.PASS);

		requireThat(campaign.development().working(), "working").isEqualTo(promoted.development().working());
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(Builder.counts(10, 0));
		requireThat(campaign.development().reverts(), "reverts").isEmpty();
	}

	/**
	 * A failure in a cell outside the promoting round cannot withdraw the candidate.
	 */
	// Requirement: R-D15
	@Test
	public void ignoresCellOutsidePromotingRound()
	{
		Campaign promoted = promote(predecessor(99, 1, Map.of("safety", 1)));
		Campaign campaign = TransitionSteps.adopt(promoted, Purpose.DEVELOPMENT, "formatting", Verdict.FAIL);

		requireThat(campaign.development().working(), "working").isEqualTo(promoted.development().working());
		requireThat(campaign.development().count("formatting"), "counts").isEqualTo(Builder.counts(0, 1));
	}

	/**
	 * A predecessor without representative evidence in an addressed cell cannot trigger a revert there.
	 */
	// Requirement: R-D15
	@Test
	public void ignoresUnmeasuredPredecessor()
	{
		Campaign promoted = promote(predecessor(0, 0, Map.of("safety", 1)));
		Campaign campaign = TransitionSteps.adopt(promoted, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(campaign.development().working(), "working").isEqualTo(promoted.development().working());
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(Builder.counts(0, 1));
	}

	/**
	 * The third failure's revert replaces the edit that the failure-count rule would start.
	 */
	// Requirement: R-D15
	@Test
	public void revertsBeforeFailureCountStartsEdit()
	{
		Campaign predecessor = predecessor(13, 7, Map.of("safety", 1));
		Campaign campaign = promote(predecessor);
		for (int outcome = 0; outcome < 3; ++outcome)
			campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(campaign.development().working(), "working").isEqualTo(predecessor.development().working());
		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().author(), "author").isNull();
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.outcome(), "outcome").isNull();
	}

	/**
	 * An unproductive first attempt restores its round so that the second attempt remains available.
	 */
	// Requirement: R-D15
	@Test
	public void restoresRemainingRoundAttempt()
	{
		Campaign predecessor = predecessor(99, 1, Map.of("safety", 0));
		Campaign campaign = TransitionSteps.adopt(promote(predecessor), Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(campaign.development().working(), "working").isEqualTo(predecessor.development().working());
		requireThat(campaign.development().round().attempts(), "attempts").isEqualTo(Map.of("safety", 1));
		requireThat(campaign.development().round().nextCell().orElseThrow(), "nextCell").isEqualTo("safety");
		requireThat(campaign.pending(), "pendingDecision").isNull();
	}

	/**
	 * Returns a candidate with retained counts and a reproduced triggering instance.
	 *
	 * @param passes the predecessor's safety passes
	 * @param failures the predecessor's safety failures
	 * @param attempts the round's completed attempts
	 * @return the predecessor campaign
	 */
	private static Campaign predecessor(int passes, int failures, Map<String, Integer> attempts)
	{
		TrialInstance logs = Builder.targeted("safety", "logs");
		DiagnosticRerun failed = new DiagnosticRerun(Verdict.FAIL, "The same defect reproduced");
		return new Builder().confirmed().working(1).development("safety", passes, failures).
			development("formatting", 61, 0).failure(new Failure(logs, "The directory was removed", List.of(), null,
				List.of(failed, failed, failed), List.of(), Builder.FILES)).regression(logs, Verdict.FAIL).
			round(new Round(attempts)).build();
	}

	/**
	 * Promotes a passing attempt through the same durable transitions as the driver.
	 *
	 * @param predecessor the campaign before promotion
	 * @return the promoted campaign
	 */
	private static Campaign promote(Campaign predecessor)
	{
		Sha256 snapshot = Builder.snapshot("promoted-revert-candidate");
		Campaign campaign = TransitionSteps.submit(predecessor, "safety", snapshot, List.of());
		TrialInstance instance = predecessor.development().failures().getFirst().instance();
		int number = campaign.development().author().number();
		for (int run = 0; run < 2; ++run)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, number, snapshot, instance, Verdict.PASS);
		return Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(snapshot));
	}
}
