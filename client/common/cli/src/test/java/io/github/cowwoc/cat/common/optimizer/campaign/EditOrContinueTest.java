package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

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
		return new CampaignBuilder().confirmed().development("safety", 60, safetyFailures).diagnosis(diagnosis).
			build().development();
	}

	/**
	 * Returns the instances of a development state's failures.
	 *
	 * @param development the state
	 * @return the instances, in failure order
	 */
	private static List<TrialInstance> instances(Development development)
	{
		return development.failures().stream().map(Failure::instance).toList();
	}

	/**
	 * Verifies that a reproduced failure leads to an edit.
	 */
	@Test
	public void reproducedFailureEdits()
	{
		// Requirement: R-D2
		TrialInstance failure = CampaignBuilder.targeted("safety", "logs");
		requireThat(EditOrContinue.decide(development(1, new Diagnosis(List.of(failure), 1, null, true))),
			"reproduced").isEqualTo(EditOrContinue.Decision.EDIT);
		requireThat(EditOrContinue.decide(development(1, new Diagnosis(List.of(failure), 1, null, false))),
			"notReproduced").isEqualTo(EditOrContinue.Decision.RERUN);
	}

	/**
	 * Verifies that more than 2 failures in a category lead to an edit, and that 2 do not.
	 */
	@Test
	public void moreThanTwoFailuresEdit()
	{
		// Requirement: R-D2
		TrialInstance failure = CampaignBuilder.targeted("safety", "logs");
		requireThat(EditOrContinue.decide(development(3, new Diagnosis(List.of(failure), 0, null, false))), "three").
			isEqualTo(EditOrContinue.Decision.EDIT);
		requireThat(EditOrContinue.decide(development(2, new Diagnosis(List.of(failure), 0, null, false))), "two").
			isEqualTo(EditOrContinue.Decision.RERUN);
	}

	/**
	 * Verifies that sampling continues once every pending rerun has passed.
	 */
	@Test
	public void passingRerunsContinue()
	{
		// Requirement: R-D2
		requireThat(EditOrContinue.decide(development(2, new Diagnosis(List.of(), 0, null, false))), "done").
			isEqualTo(EditOrContinue.Decision.CONTINUE);
	}

	/**
	 * Verifies that an adopted failure pauses representative admissions while in-flight trials finish, and that the
	 * failing instance reruns as a targeted diagnostic outside the counts.
	 */
	@Test
	public void failurePausesAndReruns()
	{
		// Requirement: R-D2
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey inFlight = TransitionSteps.last(campaign);
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(inFlight));
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);

		requireThat(campaign.development().isPaused(), "isPaused").isTrue();
		TrialInstance failed = campaign.development().failures().getFirst().instance();
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
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).slowStart(new SlowStart(4, 0)).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);
		TrialInstance failed = campaign.development().failures().getFirst().instance();
		Development development = campaign.development();
		for (int rerun = 0; rerun < Diagnosis.RERUNS; ++rerun)
		{
			requireThat(campaign.development().diagnosis(), "diagnosis[" + rerun + "]").isNotNull();
			campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
				development.working().snapshot(), failed, Verdict.PASS);
		}
		requireThat(campaign.development().isPaused(), "isPaused").isFalse();
		requireThat(instances(campaign.development()), "failures").containsExactly(List.of(failed));
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(
			CampaignBuilder.counts(60, 0).add(Verdict.FAIL));
		requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(1);
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").size().isEqualTo(1);
	}

	/**
	 * Verifies that a failing rerun starts a development round over the failing categories.
	 */
	@Test
	public void failingRerunStartsRound()
	{
		// Requirement: R-D2
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 60, 0).
			development("formatting", 60, 0).build();
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "safety", Verdict.FAIL);
		TrialInstance failed = campaign.development().failures().getFirst().instance();
		Development development = campaign.development();
		campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
			development.working().snapshot(), failed, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
			development.working().snapshot(), failed, Verdict.FAIL);

		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 0)));
		requireThat(instances(campaign.development()), "failures").containsExactly(List.of(failed));
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(
			CampaignBuilder.counts(60, 0).add(Verdict.FAIL));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}

	/**
	 * Verifies that a failure keeps the judge's rationale and its diagnosis after 3 passing reruns resume sampling.
	 */
	@Test
	public void continuedFailureKeepsItsDiagnosis()
	{
		// Requirement: R-D2
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 60, 0).build();
		campaign = judgeLast(TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety"), Verdict.FAIL,
			"The reply deleted the logs directory without asking.");
		TrialInstance failed = campaign.development().failures().getFirst().instance();
		List<DiagnosticRerun> reruns = new ArrayList<>();
		for (int rerun = 1; rerun <= Diagnosis.RERUNS; ++rerun)
		{
			String rationale = "Rerun " + rerun + " asked before deleting.";
			campaign = judgeLast(assignRerun(campaign, failed), Verdict.PASS, rationale);
			reruns.add(new DiagnosticRerun(Verdict.PASS, rationale));
		}

		requireThat(campaign.development().isPaused(), "isPaused").isFalse();
		requireThat(campaign.development().failures(), "failures").isEqualTo(List.of(new Failure(failed,
			"The reply deleted the logs directory without asking.", reruns, List.of())));
	}

	/**
	 * Verifies that a failure keeps the judge's rationale and its diagnosis after a failing rerun starts a
	 * development round.
	 */
	@Test
	public void editedFailureKeepsItsDiagnosis()
	{
		// Requirement: R-D2
		Campaign campaign = new CampaignBuilder().confirmed().development("safety", 60, 0).build();
		campaign = judgeLast(TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety"), Verdict.FAIL,
			"The reply deleted the logs directory without asking.");
		TrialInstance failed = campaign.development().failures().getFirst().instance();
		campaign = judgeLast(assignRerun(campaign, failed), Verdict.PASS, "The rerun asked before deleting.");
		campaign = judgeLast(assignRerun(campaign, failed), Verdict.FAIL, "The rerun deleted the directory too.");

		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 0)));
		requireThat(campaign.development().failures(), "failures").isEqualTo(List.of(new Failure(failed,
			"The reply deleted the logs directory without asking.", List.of(
				new DiagnosticRerun(Verdict.PASS, "The rerun asked before deleting."),
				new DiagnosticRerun(Verdict.FAIL, "The rerun deleted the directory too.")), List.of())));
	}

	/**
	 * Assigns the diagnostic rerun of the current failing instance.
	 *
	 * @param campaign the campaign, with a diagnosis in progress
	 * @param failed the failing instance
	 * @return the updated campaign
	 */
	private static Campaign assignRerun(Campaign campaign, TrialInstance failed)
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
		Campaign started = CampaignTransitions.apply(campaign, new CampaignEvent.Start(key));
		return TransitionSteps.complete(started, key, verdict, rationale);
	}
}
