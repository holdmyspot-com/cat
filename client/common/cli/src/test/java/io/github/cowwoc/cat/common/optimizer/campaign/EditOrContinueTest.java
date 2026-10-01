package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.time.Instant;
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
		TrialInstance failed = campaign.development().failures().getFirst();
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
		TrialInstance failed = campaign.development().failures().getFirst();
		Development development = campaign.development();
		for (int rerun = 0; rerun < Diagnosis.RERUNS; ++rerun)
		{
			requireThat(campaign.development().diagnosis(), "diagnosis[" + rerun + "]").isNotNull();
			campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
				development.working().snapshot(), failed, Verdict.PASS);
		}
		requireThat(campaign.development().isPaused(), "isPaused").isFalse();
		requireThat(campaign.development().failures(), "failures").containsExactly(List.of(failed));
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
		TrialInstance failed = campaign.development().failures().getFirst();
		Development development = campaign.development();
		campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
			development.working().snapshot(), failed, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.DIAGNOSTIC, development.series(),
			development.working().snapshot(), failed, Verdict.FAIL);

		requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
		requireThat(campaign.development().round(), "round").isEqualTo(new Round(Map.of("safety", 0)));
		requireThat(campaign.development().failures(), "failures").containsExactly(List.of(failed));
		requireThat(campaign.development().count("safety"), "counts").isEqualTo(
			CampaignBuilder.counts(60, 0).add(Verdict.FAIL));
		requireThat(Scheduler.plan(campaign, NOW, 8), "plan").isEmpty();
	}
}
