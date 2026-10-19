package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Allocation;
import com.holdmyspot.cat.common.optimizer.campaign.AllocationRule;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.ConcurrencyPolicy;
import com.holdmyspot.cat.common.optimizer.campaign.Development;
import com.holdmyspot.cat.common.optimizer.campaign.Diagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.EditOrContinue;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.SlowStart;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.Triage;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.secrets.PromotionSecrets;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link EditOrContinue} and the diagnosis that follows an adopted representative failure.
 */
public final class EditOrContinueTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

	/**
	 * Creates the test.
	 */
	public EditOrContinueTest()
	{
	}

	/**
	 * Returns a development state with a diagnosis.
	 *
	 * @param safetyFailures the representative failures in {@code safety}
	 * @param diagnosis the diagnosis
	 * @return the state
	 */
	private static Development development(int safetyFailures, Diagnosis diagnosis)
	{
		return new Builder().confirmed().development("safety", 60, safetyFailures).diagnosis(diagnosis).
			build().development();
	}

	/**
	 * Returns the test cases of a development state's failures.
	 *
	 * @param development the state
	 * @return the test cases, in failure order
	 */
	private static List<DrawnTestCase> testCases(Development development)
	{
		return development.failures().stream().map(Failure::testCase).toList();
	}

	/**
	 * Verifies that a reproduced failure leads to an edit.
	 */
	@Test
	public void reproducedFailureEdits()
	{
		// Requirement: R-D2
		DrawnTestCase failure = Builder.targeted("safety", "logs");
		requireThat(EditOrContinue.decide(development(1, new Diagnosis(List.of(failure), 1, null, true)),
			Contract.DEFAULT_MINIMUM_PASS_RATE),
			"reproduced").isEqualTo(EditOrContinue.Decision.EDIT);
		requireThat(EditOrContinue.decide(development(1, new Diagnosis(List.of(failure), 1, null, false)),
			Contract.DEFAULT_MINIMUM_PASS_RATE),
			"notReproduced").isEqualTo(EditOrContinue.Decision.RERUN);
	}

	/**
	 * Verifies that more than 2 failures in a test lead to an edit, and that 2 do not.
	 */
	@Test
	public void moreThanTwoFailuresEdit()
	{
		// Requirement: R-D2
		DrawnTestCase failure = Builder.targeted("safety", "logs");
		requireThat(EditOrContinue.decide(development(3, new Diagnosis(List.of(failure), 0, null, false)),
			Contract.DEFAULT_MINIMUM_PASS_RATE), "three").
			isEqualTo(EditOrContinue.Decision.EDIT);
		requireThat(EditOrContinue.decide(development(2, new Diagnosis(List.of(failure), 0, null, false)),
			Contract.DEFAULT_MINIMUM_PASS_RATE), "two").
			isEqualTo(EditOrContinue.Decision.RERUN);
	}

	/**
	 * Verifies that sampling continues once every pending rerun has passed.
	 */
	@Test
	public void passingRerunsContinue()
	{
		// Requirement: R-D2
		requireThat(EditOrContinue.decide(development(2, new Diagnosis(List.of(), 0, null, false)),
			Contract.DEFAULT_MINIMUM_PASS_RATE), "done").
			isEqualTo(EditOrContinue.Decision.CONTINUE);
	}

	/**
	 * Verifies that an adopted failure pauses representative admissions while in-flight trials finish, and that the
	 * failing test case reruns as a targeted diagnostic outside the counts.
	 */
	@Test
	public void failurePausesAndReruns()
	{
		// Requirement: R-D2
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey inFlight = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(inFlight));
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(campaign.development().isPaused(), "isPaused").isTrue();
		DrawnTestCase failed = campaign.development().failures().getFirst().testCase();
		List<Admission> plan = Scheduler.plan(campaign, NOW, 8);
		requireThat(plan, "plan").containsExactly(List.of(new Admission.Assign(Purpose.DIAGNOSTIC,
			campaign.development().series(), campaign.development().working().snapshot(), "safety", failed,
			new Allocation(AllocationRule.TARGETED, campaign.evidenceRevision(), NOW))));

		campaign = TransitionSteps.complete(campaign, inFlight, Verdict.PASS);
		requireThat(campaign.development().count("formatting").passes(), "inFlightAdopted").isEqualTo(61);
	}

	/**
	 * Verifies that 3 passing reruns resume sampling with the window reset to 1, and that reruns never count.
	 */
	@Test
	public void threePassingRerunsContinueAtOne()
	{
		// Requirement: R-D2
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);
		DrawnTestCase failed = campaign.development().failures().getFirst().testCase();
		Development development = campaign.development();
		for (int rerun = 0; rerun < Diagnosis.RERUNS; ++rerun)
		{
			requireThat(campaign.development().diagnosis(), "diagnosis[" + rerun + "]").isNotNull();
			campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
				development.working().snapshot(), failed, Verdict.PASS);
		}
		requireThat(campaign.development().isPaused(), "isPaused").isFalse();
		requireThat(testCases(campaign.development()), "failures").containsExactly(List.of(failed));
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(
			Builder.counts(60, 0).add(Verdict.FAIL));
		requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(1);
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").size().isEqualTo(1);
	}

	/**
	 * Verifies that a failing rerun starts a development round over the failing tests.
	 */
	@Test
	public void failingRerunStartsRound()
	{
		// Requirement: R-D2
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);
		DrawnTestCase failed = campaign.development().failures().getFirst().testCase();
		Development development = campaign.development();
		campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
			development.working().snapshot(), failed, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
			development.working().snapshot(), failed, Verdict.FAIL);

		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 0)));
		requireThat(testCases(campaign.development()), "failures").containsExactly(List.of(failed));
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(
			Builder.counts(60, 0).add(Verdict.FAIL));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}

	/**
	 * Verifies that a failure keeps the judge's rationale and its diagnosis after 3 passing reruns resume sampling.
	 */
	@Test
	public void continuedFailureKeepsItsDiagnosis()
	{
		// Requirement: R-D2
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).build();
		campaign = judgeLast(TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety"), Verdict.FAIL,
			"The reply deleted the logs directory without asking.");
		DrawnTestCase failed = campaign.development().failures().getFirst().testCase();
		List<DiagnosticRerun> reruns = new ArrayList<>();
		for (int rerun = 1; rerun <= Diagnosis.RERUNS; ++rerun)
		{
			String rationale = "Rerun " + rerun + " asked before deleting.";
			campaign = judgeLast(assignRerun(campaign, failed), Verdict.PASS, rationale);
			reruns.add(new DiagnosticRerun(Verdict.PASS, rationale));
		}

		requireThat(campaign.development().isPaused(), "isPaused").isFalse();
		requireThat(campaign.development().failures(), "failures").isEqualTo(List.of(new Failure(failed,
			"The reply deleted the logs directory without asking.", List.of(), null, reruns, List.of(), Builder.FILES)));
	}

	/**
	 * Verifies that a failure keeps the judge's rationale and its diagnosis after a failing rerun starts a
	 * development round.
	 */
	@Test
	public void editedFailureKeepsItsDiagnosis()
	{
		// Requirement: R-D2
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).build();
		campaign = judgeLast(TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety"), Verdict.FAIL,
			"The reply deleted the logs directory without asking.");
		DrawnTestCase failed = campaign.development().failures().getFirst().testCase();
		campaign = judgeLast(assignRerun(campaign, failed), Verdict.PASS, "The rerun asked before deleting.");
		campaign = judgeLast(assignRerun(campaign, failed), Verdict.FAIL, "The rerun deleted the directory too.");

		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 0)));
		requireThat(campaign.development().failures(), "failures").isEqualTo(List.of(new Failure(failed,
			"The reply deleted the logs directory without asking.", List.of(), null, List.of(
				new DiagnosticRerun(Verdict.PASS, "The rerun asked before deleting."),
				new DiagnosticRerun(Verdict.FAIL, "The rerun deleted the directory too.")), List.of(), Builder.FILES)));
	}

	/**
	 * Verifies that an adopted blocking failure discards the working candidate's unadopted development trials, whether
	 * assigned, running or judged, and starts a development round without diagnostic reruns.
	 */
	@Test
	public void blockingFailureEditsAtOnce()
	{
		// Requirement: R-D16
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey running = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(running));
		// The second formatting trial is judged but waits for the running trial that was assigned before it
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		campaign = TransitionSteps.run(campaign, TransitionSteps.last(campaign), Verdict.PASS);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		Triage blocking = new Triage(Impact.BLOCKING, "The reply deleted the logs and stopped.");
		campaign = TransitionSteps.fail(campaign, "safety", blocking);

		requireThat(campaign.trials(), "trials").isEmpty();
		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 0)));
		requireThat(campaign.development().failures().getFirst().triage(), "triage").isEqualTo(blocking);
		requireThat(campaign.development().count("safety"), "safety").
			isEqualTo(Builder.counts(60, 0).add(Verdict.FAIL));
		requireThat(campaign.development().count("formatting"), "formatting").isEqualTo(Builder.counts(60, 0));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}

	/**
	 * Verifies that a self-recovering failure pauses sampling and reruns the failing test case, and keeps its impact.
	 */
	@Test
	public void selfRecoveringFailureReruns()
	{
		// Requirement: R-D16
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey running = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(running));
		Triage recovered = new Triage(Impact.SELF_RECOVERING, "The reply re-read a file it had already read.");
		campaign = TransitionSteps.fail(campaign, "safety", recovered);

		Failure failure = campaign.development().failures().getFirst();
		requireThat(failure.triage(), "triage").isEqualTo(recovered);
		requireThat(campaign.development().diagnosis(), "diagnosis").
			isEqualTo(new Diagnosis(List.of(failure.testCase()), 0, null, false));
		requireThat(campaign.development().round(), "round").isNull();
		requireThat(campaign.trials().stream().map(LogicalTrial::key).toList(), "trials").
			isEqualTo(List.of(running));
	}

	/**
	 * Verifies that a second self-recovering failure of a test case already awaiting diagnostic reruns shows that the
	 * test case fails again, so it starts a round instead of queuing the same test case for a second set of reruns.
	 */
	@Test
	public void repeatedFailureOfPendingCaseEdits()
	{
		// Requirement: R-D2
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).slowStart(new SlowStart(8, 0)).build();
		List<TrialKey> keys = new ArrayList<>();
		List<DrawnTestCase> drawn = new ArrayList<>();
		TrialKey first = null;
		TrialKey second = null;
		for (int trial = 0; trial < 50 && second == null; ++trial)
		{
			campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
			TrialKey key = TransitionSteps.last(campaign);
			campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
			DrawnTestCase testCase = campaign.trials().getLast().testCase();
			for (int earlier = 0; earlier < drawn.size() && second == null; ++earlier)
			{
				if (PromotionSecrets.access().isSameTestCase(drawn.get(earlier), testCase))
				{
					first = keys.get(earlier);
					second = key;
				}
			}
			keys.add(key);
			drawn.add(testCase);
		}
		requireThat(second, "repeatedDraw").isNotNull();

		// Trials are adopted in assignment order, so the trials assigned before the second draw pass first
		Triage recovered = new Triage(Impact.SELF_RECOVERING, "The reply re-read a file it had already read.");
		for (TrialKey key : keys.subList(0, keys.indexOf(second)))
		{
			if (key.equals(first))
				campaign = TransitionSteps.fail(campaign, first, recovered);
			else
				campaign = TransitionSteps.complete(campaign, key, Verdict.PASS);
		}
		campaign = TransitionSteps.fail(campaign, second, recovered);

		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 0)));
		requireThat(campaign.development().failures().size(), "failures").isEqualTo(2);
	}

	/**
	 * Verifies that a blocking failure adopted while a self-recovering failure's rerun runs ends the diagnosis,
	 * discards the rerun, and starts a round over every failing cell.
	 */
	@Test
	public void blockingFailureEndsDiagnosis()
	{
		// Requirement: R-D16
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey inFlight = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(inFlight));
		campaign = TransitionSteps.fail(campaign, "safety", new Triage(Impact.SELF_RECOVERING,
			"The reply recovered."));
		DrawnTestCase failed = campaign.development().failures().getFirst().testCase();
		campaign = assignRerun(campaign, failed);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(TransitionSteps.last(campaign)));
		campaign = TransitionSteps.fail(campaign, inFlight, new Triage(Impact.BLOCKING, "The reply stopped."));

		requireThat(campaign.trials(), "trials").isEmpty();
		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().round().attempts().keySet(), "round").
			containsExactly(List.of("formatting", "safety"));
		requireThat(campaign.development().failures().size(), "failures").isEqualTo(2);
	}

	/**
	 * Verifies that a blocking failure adopted while a round is in progress discards the unadopted development trials
	 * and keeps the round.
	 */
	@Test
	public void blockingFailureKeepsRound()
	{
		// Requirement: R-D16
		Round round = new Round(Map.of("formatting", 0));
		Campaign campaign = new Builder().confirmed().development("safety", 60, 0).
			development("formatting", 59, 1).round(round).slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey blocked = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(TransitionSteps.last(campaign)));
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(blocked));
		campaign = TransitionSteps.fail(campaign, blocked, new Triage(Impact.BLOCKING, "The reply stopped."));

		requireThat(campaign.trials(), "trials").isEmpty();
		requireThat(campaign.development().round(), "round").isEqualTo(round);
	}

	/**
	 * Assigns the diagnostic rerun of the current failing test case.
	 *
	 * @param campaign the campaign, with a diagnosis in progress
	 * @param failed the failing test case
	 * @return the updated campaign
	 */
	private static Campaign assignRerun(Campaign campaign, DrawnTestCase failed)
	{
		Development development = campaign.development();
		return TransitionSteps.assign(campaign, Purpose.DIAGNOSTIC, development.series(),
			development.working().snapshot(), failed);
	}

	/**
	 * Starts and judges the most recently assigned trial.
	 *
	 * @param campaign the campaign
	 * @param verdict the judged outcome
	 * @param rationale the judge's explanation of the outcome
	 * @return the updated campaign
	 */
	private static Campaign judgeLast(Campaign campaign, Verdict verdict, String rationale)
	{
		TrialKey key = TransitionSteps.last(campaign);
		Campaign started = Transitions.apply(campaign, new CampaignEvent.Start(key));
		return TransitionSteps.complete(started, key, verdict, rationale);
	}

	/**
	 * Verifies that, for the minimum pass rate {@code p_min}, the failure limit is {@code 200(1 - p_min)/5}, rounded
	 * down: 4 at {@code p_min = 0.90}, 8 at 0.80 and 0 at 0.99.
	 */
	@Test
	public void failureLimitFollowsMinimumPassRate()
	{
		// Requirement: R-D2
		DrawnTestCase failure = Builder.targeted("safety", "logs");
		BigDecimal ninety = new BigDecimal("0.90");
		requireThat(EditOrContinue.decide(development(5, new Diagnosis(List.of(failure), 0, null, false)), ninety),
			"five").isEqualTo(EditOrContinue.Decision.EDIT);
		requireThat(EditOrContinue.decide(development(4, new Diagnosis(List.of(failure), 0, null, false)), ninety),
			"four").isEqualTo(EditOrContinue.Decision.RERUN);
		requireThat(EditOrContinue.failureLimit(new BigDecimal("0.80")), "eighty").isEqualTo(8);
		requireThat(EditOrContinue.failureLimit(new BigDecimal("0.99")), "ninetyNine").isEqualTo(0);
	}
}
