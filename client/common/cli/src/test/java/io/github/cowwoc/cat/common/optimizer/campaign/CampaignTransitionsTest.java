package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder.counts;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests {@link CampaignTransitions}.
 */
public final class CampaignTransitionsTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
	private static final Sha256 REVISED = CampaignBuilder.snapshot("revised contract");

	/**
	 * Creates the test.
	 */
	public CampaignTransitionsTest()
	{
	}

	/**
	 * Returns a commit event for a certification index.
	 *
	 * @param index the certification index
	 * @return the event
	 */
	private static CampaignEvent.Commit commit(int index)
	{
		return new CampaignEvent.Commit(new CapCalibration(ErrorBudget.certificationLevel(index), 262), index);
	}

	/**
	 * Returns the campaign's identity with one condition changed.
	 *
	 * @param campaign the campaign
	 * @param condition the condition
	 * @return the identity
	 */
	private static MeasurementIdentity changed(Campaign campaign, MeasurementCondition condition)
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(campaign.identity().values());
		values.put(condition, "changed");
		return new MeasurementIdentity(values);
	}

	/**
	 * Adopts 100 passing development trials per category of the incumbent, which also meets its baseline.
	 *
	 * @param campaign the campaign
	 * @return the updated campaign
	 */
	private static Campaign makeReady(Campaign campaign)
	{
		Campaign result = campaign;
		for (int trial = 0; trial < 100; ++trial)
		{
			result = TransitionSteps.adopt(result, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
			result = TransitionSteps.adopt(result, Purpose.DEVELOPMENT, "safety", Verdict.PASS);
		}
		return result;
	}

	/**
	 * Verifies that a first confirmation creates lineage 1, the first baseline and the contract's identity.
	 */
	@Test
	public void confirmationCreatesLineage()
	{
		// Requirement: R-L2
		Campaign campaign = Campaign.start(new CampaignBuilder().build().header(), 8, CampaignBuilder.INCUMBENT,
			CampaignBuilder.OBSERVED);
		requireThat(Scheduler.plan(campaign, NOW, 8), "unconfirmed").isEmpty();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Confirm(ContractFixtures.contract(),
			CampaignBuilder.contractHash(), 17));

		requireThat(campaign.lineage().number(), "lineage").isEqualTo(1);
		requireThat(campaign.lineage().contractHash(), "contractHash").isEqualTo(CampaignBuilder.contractHash());
		requireThat(campaign.baseline().index(), "baseline").isEqualTo(1);
		requireThat(campaign.identity(), "identity").isEqualTo(CampaignBuilder.confirmedIdentity("model-a"));
		requireThat(campaign.development().series(), "series").isEqualTo(1);
		requireThat(Scheduler.plan(campaign, NOW, 8).isEmpty(), "confirmed").isFalse();
	}

	/**
	 * Verifies that only a commit spends a certification index, and that failed attempts, revisions, new identities
	 * and cancel-then-resume never reset it.
	 */
	@Test
	public void certificationIndexIsPermanent()
	{
		// Requirement: R-S9
		Campaign campaign = makeReady(new CampaignBuilder().confirmed().build());
		requireThat(campaign.nextCertificationIndex(), "beforeCommit").isEqualTo(1);
		campaign = CampaignTransitions.apply(campaign, commit(1));
		requireThat(campaign.attempt(1).index(), "first").isEqualTo(1);

		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Revise());
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Confirm(ContractFixtures.contract(),
			REVISED, 18));
		requireThat(campaign.nextCertificationIndex(), "afterRevision").isEqualTo(2);

		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ChangeMeasurement(changed(campaign,
			MeasurementCondition.MODEL)));
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_IDENTITY));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Cancel());
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESUME));
		requireThat(campaign.nextCertificationIndex(), "afterResume").isEqualTo(2);

		campaign = makeReady(campaign);
		campaign = CampaignTransitions.apply(campaign, commit(2));
		requireThat(campaign.attempt(2).index(), "second").isEqualTo(2);
		requireThat(campaign.attempt(2).procedure().boundaries().level(), "level").
			isEqualTo(ErrorBudget.certificationLevel(2));

		Campaign fresh = Campaign.start(campaign.header().withLifecycle(Lifecycle.ACTIVE), 8,
			CampaignBuilder.INCUMBENT, CampaignBuilder.OBSERVED);
		requireThat(fresh.nextCertificationIndex(), "newCampaign").isEqualTo(1);
	}

	/**
	 * Verifies that a promoted candidate starts new counts and that the earlier failures stay available for
	 * diagnosis.
	 */
	@Test
	public void newCandidateStartsNewCounts()
	{
		// Requirement: R-D4
		TrialInstance logs = CampaignBuilder.targeted("safety", "logs");
		Sha256 attempt = CampaignBuilder.snapshot("attempt");
		Campaign campaign = new CampaignBuilder().confirmed().working(1).development("safety", 60, 1).
			development("formatting", 61, 0).failure(logs).regression(logs, Verdict.FAIL).
			round(new Round(Map.of("safety", 0))).build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt("safety", attempt));
		for (int run = 0; run < 2; ++run)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, attempt, logs, Verdict.PASS);

		requireThat(campaign.development().working(), "working").isEqualTo(new Candidate(2, attempt));
		requireThat(campaign.development().series(), "series").isEqualTo(2);
		requireThat(campaign.development().count("safety"), "safety").isEqualTo(CategoryCounts.EMPTY);
		requireThat(campaign.development().count("formatting"), "formatting").isEqualTo(CategoryCounts.EMPTY);
		requireThat(campaign.lineage().regressionSet().getFirst().instance(), "retained").isEqualTo(logs);
	}

	/**
	 * Returns the four certifiable-scope outcomes.
	 *
	 * @return the rows
	 */
	@DataProvider
	public static Object[][] scopeOutcomes()
	{
		return new Object[][]
			{
				{Outcome.UNSUPPORTED_INSTRUCTION_SOURCE},
				{Outcome.INELIGIBLE_TRIAL_EFFECT},
				{Outcome.UNSUPPORTED_OUTPUT},
				{Outcome.UNSUPPORTED_TEST_INTERACTION}
			};
	}

	/**
	 * Verifies that a scope condition known before a commit makes certification unavailable and blocks the commit.
	 *
	 * @param outcome the scope condition's outcome
	 */
	@Test(dataProvider = "scopeOutcomes")
	public void scopeConditionBlocksCommit(Outcome outcome)
	{
		// Requirement: R-C6
		Campaign campaign = new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ReportUnavailable(outcome));

		requireThat(campaign.attempts(), "attempts").isEmpty();
		requireThat(campaign.outcome(), "outcome").isEqualTo(outcome);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.CertificationUnavailable(outcome));
		requireThat(campaign.lineage().unavailable(), "unavailable").containsExactly(List.of(outcome));
		requireThat(campaign.choices(), "choices").
			containsExactly(List.of(Choice.ACCEPT_WITHOUT_CERTIFICATION, Choice.STOP));
		requireThat(ReadinessGate.isCommitDue(campaign), "isCommitDue").isFalse();
	}

	/**
	 * Verifies that a scope condition discovered after a commit ends the attempt with its outcome, keeps the
	 * outcomes and the spent index, and that later outcomes are dropped.
	 *
	 * @param outcome the scope condition's outcome
	 */
	@Test(dataProvider = "scopeOutcomes")
	public void scopeConditionEndsAttempt(Outcome outcome)
	{
		// Requirement: R-C6, R-F5
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			category("formatting", 40, 0).category("safety", 30, 1).build();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey inFlight = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(inFlight));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ReportUnavailable(outcome));

		CertificationAttempt attempt = campaign.attempt(1);
		requireThat(attempt.outcome(), "attempt.outcome").isEqualTo(outcome);
		requireThat(attempt.count("safety"), "kept").isEqualTo(counts(30, 1));
		requireThat(campaign.nextCertificationIndex(), "nextCertificationIndex").isEqualTo(2);
		requireThat(campaign.lineage().unavailable(), "unavailable").containsExactly(List.of(outcome));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();

		campaign = TransitionSteps.complete(campaign, inFlight, Verdict.PASS);
		requireThat(campaign.attempt(1).count("safety"), "afterLate").isEqualTo(counts(30, 1));
		requireThat(campaign.attempt(1).outcome(), "afterLateOutcome").isEqualTo(outcome);
	}

	/**
	 * Verifies that a scope disposition is kept in the campaign state across a stop and resume, so a reloaded
	 * campaign still refuses a commit.
	 */
	@Test
	public void unavailabilitySurvivesResume()
	{
		// Requirement: R-F5
		Campaign campaign = new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).category("safety", 30, 1).build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ReportUnavailable(
			Outcome.INELIGIBLE_TRIAL_EFFECT));
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.STOP));
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESUME));
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.INELIGIBLE_TRIAL_EFFECT);
		requireThat(campaign.lineage().unavailable(), "unavailable").
			containsExactly(List.of(Outcome.INELIGIBLE_TRIAL_EFFECT));
		requireThat(campaign.pending(), "pending").
			isEqualTo(new PendingDecision.CertificationUnavailable(Outcome.INELIGIBLE_TRIAL_EFFECT));
		requireThat(ReadinessGate.isCommitDue(campaign), "isCommitDue").isFalse();
	}

	/**
	 * Verifies that a run observed under other conditions yields no outcome, is kept as an observation without
	 * counting as an infrastructure failure, and reruns the same instance after a restore.
	 */
	@Test
	public void changedConditionsYieldNoOutcome()
	{
		// Requirement: R-M5
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey key = TransitionSteps.last(campaign);
		TrialInstance instance = campaign.trials().getFirst().instance();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
		for (int failure = 0; failure < Campaign.INFRASTRUCTURE_FAILURE_LIMIT - 1; ++failure)
		{
			campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INFRASTRUCTURE,
				"provider error", NOW, null));
			campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
		}
		MeasurementIdentity observed = changed(campaign, MeasurementCondition.MODEL);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.FAIL, "", observed));

		LogicalTrial trial = campaign.trials().getFirst();
		requireThat(trial.state(), "state").isEqualTo(TrialState.ASSIGNED);
		requireThat(trial.observations(), "observations").isEqualTo(1);
		requireThat(trial.infrastructureFailures(), "infrastructureFailures").isEqualTo(2);
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(counts(10, 0));
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.MeasurementChanged(observed,
			List.of(MeasurementCondition.MODEL)));
		requireThat(campaign.choices(), "choices").
			containsExactly(List.of(Choice.RESTORE, Choice.NEW_IDENTITY, Choice.STOP));
		requireThat(Scheduler.plan(campaign, NOW, 8), "paused").isEmpty();

		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESTORE));
		requireThat(Scheduler.plan(campaign, NOW, 8).getFirst(), "rerun").isEqualTo(new Admission.Rerun(key));
		requireThat(campaign.trials().getFirst().instance(), "instance").isEqualTo(instance);
	}

	/**
	 * Returns the version conditions.
	 *
	 * @return one condition per row
	 */
	@DataProvider
	public static Object[][] versionConditions()
	{
		return new Object[][]
			{
				{MeasurementCondition.RUNNER_VERSION},
				{MeasurementCondition.CAT_VERSION}
			};
	}

	/**
	 * Verifies that a trial observed under another runner or CAT version raises a measurement change, because either
	 * version can change trial outcomes.
	 *
	 * @param condition the version condition that changes
	 */
	@Test(dataProvider = "versionConditions")
	public void versionChangeIsMeasurementChange(MeasurementCondition condition)
	{
		// Requirement: R-M5
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey key = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
		MeasurementIdentity observed = changed(campaign, condition);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.PASS, "", observed));

		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.MeasurementChanged(observed,
			List.of(condition)));
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(counts(10, 0));
	}

	/**
	 * Returns a campaign whose {@code formatting} category reached its review checkpoint while a {@code safety}
	 * trial still runs.
	 *
	 * @return the campaign
	 */
	private static Campaign checkpointWhileRunning()
	{
		Campaign campaign = new CampaignBuilder().confirmed().development("formatting", 197, List.of(0, 0, 0, 2)).
			development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(TransitionSteps.last(campaign)));
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		requireThat(campaign.pending(), "checkpoint").isEqualTo(new PendingDecision.CheckpointReached("formatting",
			200));
		return campaign;
	}

	/**
	 * Verifies that a decision raised while another one is pending waits behind it rather than being dropped.
	 */
	@Test
	public void laterDecisionWaitsBehindPendingOne()
	{
		// Requirement: R-M5
		Campaign campaign = checkpointWhileRunning();
		TrialKey running = campaign.trials().getFirst().key();
		MeasurementIdentity observed = changed(campaign, MeasurementCondition.MODEL);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Complete(running, Verdict.PASS, "", observed));
		requireThat(campaign.pending(), "first").isEqualTo(new PendingDecision.CheckpointReached("formatting", 200));

		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.moreEvidence(300));
		requireThat(campaign.pending(), "second").isEqualTo(new PendingDecision.MeasurementChanged(observed,
			List.of(MeasurementCondition.MODEL)));
		requireThat(campaign.choices(), "choices").
			containsExactly(List.of(Choice.RESTORE, Choice.NEW_IDENTITY, Choice.STOP));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}

	/**
	 * Verifies that a decision equal to one already pending is asked only once.
	 */
	@Test
	public void equalDecisionIsAskedOnce()
	{
		// Requirement: R-M5
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey first = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey second = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(first));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(second));
		MeasurementIdentity observed = changed(campaign, MeasurementCondition.MODEL);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Complete(first, Verdict.PASS, "", observed));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Complete(second, Verdict.PASS, "", observed));

		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESTORE));
		requireThat(campaign.pending(), "pending").isNull();
	}

	/**
	 * Verifies that the third infrastructure failure of one logical trial pauses the campaign, and that a retry
	 * resumes it.
	 */
	@Test
	public void thirdInfrastructureFailurePauses()
	{
		// Requirement: R-M5
		Campaign campaign = new CampaignBuilder().confirmed().build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey key = TransitionSteps.last(campaign);
		for (int failure = 0; failure < Campaign.INFRASTRUCTURE_FAILURE_LIMIT; ++failure)
		{
			requireThat(campaign.pending(), "pending[" + failure + "]").isNull();
			campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
			campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Fail(key, RunFailure.INFRASTRUCTURE,
				"provider error", NOW, null));
		}
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.TrialFailures(key));
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RETRY));
		requireThat(campaign.pending(), "retried").isNull();
		requireThat(campaign.trials().getFirst().infrastructureFailures(), "reset").isEqualTo(0);
	}

	/**
	 * Verifies that an assignment records the allocation decision that the scheduler planned it with.
	 */
	@Test
	public void assignmentRecordsAllocation()
	{
		// Requirement: R-E1
		Campaign campaign = new CampaignBuilder().confirmed().evidenceRevision(5).build();
		Admission.Assign admission = (Admission.Assign) Scheduler.plan(campaign, NOW, 1).getFirst();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Assign(admission));

		requireThat(campaign.trials().getFirst().allocation(), "allocation").
			isEqualTo(new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 5, NOW));
	}

	/**
	 * Verifies that an assignment that claims evidence the campaign has not adopted yet is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*evidence revision 6.*adopted 5.*")
	public void rejectsUnadoptedEvidenceRevision()
	{
		// Requirement: R-E1
		Campaign campaign = new CampaignBuilder().confirmed().evidenceRevision(5).build();
		CampaignTransitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(Purpose.DEVELOPMENT, 1,
			CampaignBuilder.INCUMBENT, "safety", null, new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 6,
			NOW))));
	}

	/**
	 * Verifies that an assignment whose allocation rule cannot choose its purpose is rejected.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*allocation\\.rule\\(\\)\\.assigns\\(purpose\\).*")
	public void allocationRuleMustAssignPurpose()
	{
		// Requirement: R-E1
		new Admission.Assign(Purpose.DEVELOPMENT, 1, CampaignBuilder.INCUMBENT, "safety", null,
			new Allocation(AllocationRule.BASELINE_ARM, 0, NOW));
	}

	/**
	 * Verifies that a trial cannot count more infrastructure failures toward the limit than the infrastructure
	 * attempts it records.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*infrastructureFailures.*")
	public void failureCountNeedsRecordedAttempts()
	{
		// Requirement: R-E1
		Campaign campaign = TransitionSteps.assign(new CampaignBuilder().confirmed().build(), Purpose.DEVELOPMENT,
			"safety");
		LogicalTrial trial = campaign.trials().getFirst();
		List<InfrastructureAttempt> attempts = List.of(new InfrastructureAttempt(NOW, RunFailure.RATE_LIMITED,
			"The provider returned HTTP 429"));
		new LogicalTrial(trial.key(), trial.instance(), trial.identity(), trial.allocation(), TrialState.ASSIGNED,
			null, null, 1, attempts, 0);
	}

	/**
	 * Verifies that the evidence revision counts adopted outcomes: a judged outcome that waits for an earlier trial
	 * does not advance it, and adopting both advances it by 2.
	 */
	@Test
	public void adoptionAdvancesEvidenceRevision()
	{
		// Requirement: R-E1
		Campaign campaign = new CampaignBuilder().confirmed().evidenceRevision(5).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey first = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey second = TransitionSteps.last(campaign);

		campaign = TransitionSteps.run(campaign, second, Verdict.PASS);
		requireThat(campaign.evidenceRevision(), "waiting").isEqualTo(5L);
		campaign = TransitionSteps.run(campaign, first, Verdict.PASS);
		requireThat(campaign.evidenceRevision(), "adopted").isEqualTo(7L);
	}

	/**
	 * Verifies that every run that ends without an outcome is recorded on the trial with its time, classification
	 * and detail, and that a retry resets only the count toward the limit.
	 */
	@Test
	public void failedRunsAreInfrastructureAttempts()
	{
		// Requirement: R-E1
		Campaign campaign = new CampaignBuilder().confirmed().build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey key = TransitionSteps.last(campaign);
		List<InfrastructureAttempt> expected = List.of(
			new InfrastructureAttempt(NOW, RunFailure.INFRASTRUCTURE, "provider returned HTTP 500"),
			new InfrastructureAttempt(NOW.plusSeconds(10), RunFailure.RATE_LIMITED, "rate limit reached"),
			new InfrastructureAttempt(NOW.plusSeconds(20), RunFailure.INTERRUPTED, "slice ended"),
			new InfrastructureAttempt(NOW.plusSeconds(30), RunFailure.INFRASTRUCTURE, "runner crashed"),
			new InfrastructureAttempt(NOW.plusSeconds(40), RunFailure.INFRASTRUCTURE, "invalid judge result"));
		for (InfrastructureAttempt attempt : expected)
		{
			campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
			campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Fail(key, attempt.failure(),
				attempt.detail(), attempt.endedAt(), null));
		}
		LogicalTrial trial = campaign.trials().getFirst();
		requireThat(trial.infrastructureAttempts(), "infrastructureAttempts").isEqualTo(expected);
		requireThat(trial.infrastructureFailures(), "infrastructureFailures").isEqualTo(3);
		requireThat(campaign.pending(), "pending").isEqualTo(new PendingDecision.TrialFailures(key));

		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RETRY));
		trial = campaign.trials().getFirst();
		requireThat(trial.infrastructureFailures(), "retried").isEqualTo(0);
		requireThat(trial.infrastructureAttempts(), "kept").isEqualTo(expected);
	}

	/**
	 * Verifies that a new measurement identity during an attempt ends it {@code testing environment changed}, keeps
	 * its index spent and starts a new baseline, and that the attempt never resumes.
	 */
	@Test
	public void newIdentityEndsAttempt()
	{
		// Requirement: R-M5
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			category("safety", 30, 0).build();
		MeasurementIdentity observed = changed(campaign, MeasurementCondition.JUDGE);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ChangeMeasurement(observed));
		requireThat(campaign.runningAttempt().isPresent(), "pausedAttemptRuns").isTrue();
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_IDENTITY));

		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.TESTING_ENVIRONMENT_CHANGED);
		requireThat(campaign.attempt(1).count("safety"), "kept").isEqualTo(counts(30, 0));
		requireThat(campaign.runningAttempt().isEmpty(), "runningAttempt.isEmpty()").isTrue();
		requireThat(campaign.nextCertificationIndex(), "nextCertificationIndex").isEqualTo(2);
		requireThat(campaign.identity(), "identity").isEqualTo(observed);
		requireThat(campaign.baseline().index(), "baseline").isEqualTo(2);
		requireThat(campaign.development().series(), "series").isEqualTo(2);
		requireThat(campaign.pending(), "pending").isNull();
	}

	/**
	 * Verifies that restoring the condition lets the attempt continue.
	 */
	@Test
	public void restoreContinuesAttempt()
	{
		// Requirement: R-M5
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			category("safety", 30, 0).build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ChangeMeasurement(changed(campaign,
			MeasurementCondition.MODEL)));
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESTORE));
		requireThat(campaign.runningAttempt().isPresent(), "isPresent").isTrue();
		requireThat(Scheduler.plan(campaign, NOW, 8).isEmpty(), "plan.isEmpty()").isFalse();
	}

	/**
	 * Verifies that a loader-configuration change offers only restoring it or starting a new campaign.
	 */
	@Test
	public void loaderChangeOffersNewCampaign()
	{
		// Requirement: R-M5
		Campaign campaign = new CampaignBuilder().confirmed().build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ChangeMeasurement(changed(campaign,
			MeasurementCondition.LOADER_CONFIGURATION)));
		requireThat(campaign.choices(), "choices").
			containsExactly(List.of(Choice.RESTORE, Choice.NEW_CAMPAIGN, Choice.STOP));
		Campaign closed = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_CAMPAIGN));
		requireThat(closed.lifecycle(), "lifecycle").isEqualTo(Lifecycle.CLOSED);
	}

	/**
	 * Verifies that a loader-configuration change cannot start a new measurement identity.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void loaderChangeRefusesNewIdentity()
	{
		// Requirement: R-M5
		Campaign campaign = new CampaignBuilder().confirmed().build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.ChangeMeasurement(changed(campaign,
			MeasurementCondition.LOADER_CONFIGURATION)));
		CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.NEW_IDENTITY));
	}

	/**
	 * Verifies that a revision creates a new lineage with a new baseline, keeps earlier lineages and continues the
	 * certification index.
	 */
	@Test
	public void revisionCreatesLineage()
	{
		// Requirement: R-L2
		Campaign campaign = new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			certificationAttempt(1).outcome(Outcome.BELOW_MINIMUM).build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Revise());
		requireThat(campaign.revisionRequested(), "revisionRequested").isTrue();
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Confirm(ContractFixtures.contract(),
			REVISED, 23));

		requireThat(campaign.lineages().size(), "lineages").isEqualTo(2);
		requireThat(campaign.lineage().number(), "lineage").isEqualTo(2);
		requireThat(campaign.identity().values().get(MeasurementCondition.CONTRACT), "contract").
			isEqualTo(REVISED.hex());
		requireThat(campaign.baseline().index(), "baseline").isEqualTo(2);
		requireThat(campaign.baseline().count("safety"), "baselineCounts").isEqualTo(CategoryCounts.EMPTY);
		requireThat(campaign.development().count("safety"), "developmentCounts").isEqualTo(CategoryCounts.EMPTY);
		requireThat(campaign.nextCertificationIndex(), "nextCertificationIndex").isEqualTo(2);
		requireThat(campaign.revisionRequested(), "revised").isFalse();
	}

	/**
	 * Verifies that a confirmed contract is replaced only through a revision.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void confirmationRequiresRevision()
	{
		// Requirement: R-L2
		CampaignTransitions.apply(new CampaignBuilder().confirmed().build(),
			new CampaignEvent.Confirm(ContractFixtures.contract(), REVISED, 23));
	}

	/**
	 * Verifies that a revision during formal testing stops the attempt, which never resumes, and waits for in-flight
	 * sessions before the new contract is confirmed.
	 */
	@Test
	public void revisionStopsAttemptAtSafeCheckpoint()
	{
		// Requirement: R-L3
		Campaign campaign = new CampaignBuilder().confirmed().working(1).certificationAttempt(1).
			category("safety", 30, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialKey inFlight = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(inFlight));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Revise());
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.STOPPED_FOR_CONTRACT_REVISION);

		Campaign unsafe = campaign;
		boolean refused = false;
		try
		{
			CampaignTransitions.apply(unsafe, new CampaignEvent.Confirm(ContractFixtures.contract(), REVISED, 3));
		}
		catch (IllegalStateException _)
		{
			refused = true;
		}
		requireThat(refused, "refusedWhileRunning").isTrue();

		campaign = TransitionSteps.complete(campaign, inFlight, Verdict.PASS);
		requireThat(campaign.attempt(1).count("safety"), "counts").isEqualTo(counts(30, 0));
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Confirm(ContractFixtures.contract(),
			REVISED, 3));
		requireThat(campaign.attempt(1).outcome(), "stillStopped").isEqualTo(Outcome.STOPPED_FOR_CONTRACT_REVISION);
		requireThat(campaign.runningAttempt().isEmpty(), "runningAttempt.isEmpty()").isTrue();
	}

	/**
	 * Verifies that cancelling schedules nothing and keeps the history, and that resuming continues from it.
	 */
	@Test
	public void cancelAndResume()
	{
		// Requirement: R-L4
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 40, 1).build();
		Campaign cancelled = CampaignTransitions.apply(campaign, new CampaignEvent.Cancel());
		requireThat(cancelled.lifecycle(), "lifecycle").isEqualTo(Lifecycle.CANCELLED);
		requireThat(Scheduler.plan(cancelled, NOW, 8), "plan").isEmpty();
		requireThat(cancelled.choices(), "choices").containsExactly(List.of(Choice.RESUME));

		Campaign resumed = CampaignTransitions.apply(cancelled, CampaignEvent.Choose.of(Choice.RESUME));
		requireThat(resumed, "resumed").isEqualTo(campaign);
	}

	/**
	 * Verifies that stopping at a pending decision cancels the campaign and keeps the decision for a resume.
	 */
	@Test
	public void stopKeepsPendingDecision()
	{
		// Requirement: R-L4
		PendingDecision unowned = new PendingDecision.UnownedChange("edited CLAUDE.md");
		Campaign campaign = new CampaignBuilder().confirmed().pending(unowned).build();
		requireThat(campaign.choices(), "choices").containsExactly(List.of(Choice.RESUME, Choice.STOP));
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.STOP));
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.CANCELLED);
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESUME));
		requireThat(campaign.lifecycle(), "resumed").isEqualTo(Lifecycle.ACTIVE);
		requireThat(campaign.pending(), "pending").isEqualTo(unowned);
	}

	/**
	 * Verifies that finishing a cancelled campaign answers none of its pending decisions, which stay recorded.
	 */
	@Test
	public void cancelledFinishKeepsPendingDecisions()
	{
		// Requirement: R-L4
		PendingDecision unowned = new PendingDecision.UnownedChange("edited CLAUDE.md");
		Campaign campaign = new CampaignBuilder().confirmed().working(1).pending(unowned).
			lifecycle(Lifecycle.CANCELLED).build();
		campaign = CampaignTransitions.apply(campaign,
			CampaignEvent.Choose.of(Choice.ACCEPT_WITHOUT_CERTIFICATION));
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
		requireThat(campaign.pendingDecisions(), "pendingDecisions").isEqualTo(List.of(unowned));
	}

	/**
	 * Verifies that a closed campaign cannot resume.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void closedCampaignCannotResume()
	{
		// Requirement: R-L4
		Campaign campaign = CampaignTransitions.apply(new CampaignBuilder().confirmed().build(),
			new CampaignEvent.Close());
		requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.CLOSED);
		CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESUME));
	}

	/**
	 * Verifies that an unowned change pauses the campaign until the user continues.
	 */
	@Test
	public void unownedChangePauses()
	{
		// Requirement: R-L4
		Campaign campaign = CampaignTransitions.apply(new CampaignBuilder().confirmed().build(),
			new CampaignEvent.DetectUnownedChange("edited CLAUDE.md"));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESUME));
		requireThat(campaign.pending(), "pending").isNull();
		requireThat(Scheduler.plan(campaign, NOW, 8).isEmpty(), "resumed").isFalse();
	}
}
