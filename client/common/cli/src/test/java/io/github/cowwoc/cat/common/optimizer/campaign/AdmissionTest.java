package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests the admission blocks: calibration failures and the incumbent's baseline arm.
 */
public final class AdmissionTest
{
	private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

	/**
	 * Creates the test.
	 */
	public AdmissionTest()
	{
	}

	/**
	 * Verifies that a calibration failure leaves the attempt uncommitted and blocks admission until a retry.
	 */
	@Test
	public void calibrationFailureBlocksAdmission()
	{
		// Requirement: R-S6
		Campaign campaign = new CampaignBuilder().confirmed().working(1).readyDevelopment().readyBaseline().build();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.CalibrationFailed("no cap below 10000"));

		requireThat(campaign.attempts(), "attempts").isEmpty();
		requireThat(campaign.nextCertificationIndex(), "nextCertificationIndex").isEqualTo(1);
		requireThat(campaign.pending(), "pending").
			isEqualTo(new PendingDecision.CalibrationFailure("no cap below 10000"));
		requireThat(ReadinessGate.isCommitDue(campaign), "isCommitDue").isFalse();
		requireThat(campaign.choices(), "choices").containsExactly(List.of(Choice.RETRY, Choice.STOP));

		campaign = CampaignTransitions.apply(campaign, CampaignEvent.Choose.of(Choice.RETRY));
		requireThat(ReadinessGate.isCommitDue(campaign), "retried").isTrue();
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Commit(
			new CapCalibration(ErrorBudget.certificationLevel(1), 262), 5));
		requireThat(campaign.attempt(1).index(), "index").isEqualTo(1);
	}

	/**
	 * Verifies that a pending calibration failure blocks every admission.
	 */
	@Test
	public void pendingCalibrationFailureBlocksAdmission()
	{
		// Requirement: R-S6
		CampaignBuilder builder = new CampaignBuilder().confirmed().working(1).development("formatting", 10, 0).
			development("safety", 10, 0).baseline("safety", 10, 0);
		requireThat(Scheduler.plan(builder.build(), NOW, 8).isEmpty(), "unblocked").isFalse();
		Campaign blocked = builder.pending(new PendingDecision.CalibrationFailure("no cap below 10000")).build();
		requireThat(Scheduler.plan(blocked, NOW, 8), "blocked").isEmpty();
	}

	/**
	 * Verifies that the baseline arm runs the incumbent on the capacity left after the working candidate's window,
	 * up to the category's target.
	 */
	@Test
	public void baselineArmUsesRemainingCapacity()
	{
		// Requirement: R-D7
		Campaign campaign = new CampaignBuilder().confirmed().working(1).development("formatting", 10, 0).
			development("safety", 10, 0).baseline("formatting", 50, 0).baseline("safety", 47, 0).build();
		List<Admission> plan = Scheduler.plan(campaign, NOW, 8);
		List<Admission> baseline = new ArrayList<>();
		for (Admission admission : plan)
		{
			if (admission.purpose() == Purpose.BASELINE)
				baseline.add(admission);
		}
		Admission arm = new Admission.Assign(Purpose.BASELINE, 1, CampaignBuilder.INCUMBENT, "safety", null);
		requireThat(baseline, "baseline").isEqualTo(List.of(arm, arm, arm));
		requireThat(plan.size(), "size").isEqualTo(4);
	}

	/**
	 * Verifies that the baseline arm stops once every target is met, and that it never runs while the incumbent is
	 * the working candidate.
	 */
	@Test
	public void baselineArmStopsAtTarget()
	{
		// Requirement: R-D7
		Campaign met = new CampaignBuilder().confirmed().working(1).development("formatting", 10, 0).
			development("safety", 10, 0).readyBaseline().build();
		for (Admission admission : Scheduler.plan(met, NOW, 8))
			requireThat(admission.purpose(), "met.purpose").isNotEqualTo(Purpose.BASELINE);

		Campaign incumbent = new CampaignBuilder().confirmed().development("formatting", 10, 0).
			development("safety", 10, 0).build();
		for (Admission admission : Scheduler.plan(incumbent, NOW, 8))
			requireThat(admission.purpose(), "incumbent.purpose").isEqualTo(Purpose.DEVELOPMENT);
	}

	/**
	 * Verifies that baseline trials already in flight count toward the target.
	 */
	@Test
	public void inFlightBaselineTrialsCount()
	{
		// Requirement: R-D7
		Campaign campaign = new CampaignBuilder().confirmed().working(1).development("formatting", 10, 0).
			development("safety", 10, 0).baseline("formatting", 50, 0).baseline("safety", 48, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.BASELINE, "safety");
		campaign = CampaignTransitions.apply(campaign, new CampaignEvent.Start(TransitionSteps.last(campaign)));
		List<Admission> baseline = new ArrayList<>();
		for (Admission admission : Scheduler.plan(campaign, NOW, 8))
		{
			if (admission.purpose() == Purpose.BASELINE)
				baseline.add(admission);
		}
		requireThat(baseline, "baseline").size().isEqualTo(1);
	}
}
