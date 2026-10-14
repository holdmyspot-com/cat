package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.AllocationRule;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.CommitGate;
import io.github.cowwoc.cat.common.optimizer.campaign.Scheduler;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests trial admission during calibration failures and incumbent measurement. The baseline trials are
 * representative trials of the incumbent using capacity left by the working candidate.
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
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().build();
		campaign = Transitions.apply(campaign, new CampaignEvent.CalibrationFailed("no cap below 10000"));

		requireThat(campaign.attempts(), "attempts").isEmpty();
		requireThat(campaign.nextCertificationIndex(), "nextCertificationIndex").isEqualTo(1);
		requireThat(campaign.pending(), "pending").
			isEqualTo(new PendingDecision.CalibrationFailure("no cap below 10000"));
		requireThat(CommitGate.isCommitDue(campaign), "isCommitDue").isFalse();
		requireThat(campaign.choices(), "choices").containsExactly(List.of(Choice.RETRY, Choice.STOP));

		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.RETRY));
		requireThat(CommitGate.isCommitDue(campaign), "retried").isTrue();
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(
			new CapCalibration(ErrorBudget.certificationLevel(1), Contract.DEFAULT_MINIMUM_PASS_RATE, 262), 5,
			Builder.CONTROL));
		requireThat(campaign.attempt(1).index(), "index").isEqualTo(1);
	}

	/**
	 * Verifies that a pending calibration failure blocks every admission.
	 */
	@Test
	public void pendingCalibrationFailureBlocksAdmission()
	{
		// Requirement: R-S6
		Builder builder = new Builder().confirmed().working(1).development("formatting", 10, 0).
			development("safety", 10, 0).baseline("safety", 10, 0);
		requireThat(Scheduler.plan(builder.build(), NOW, 8).isEmpty(), "unblocked").isFalse();
		Campaign blocked = builder.pending(new PendingDecision.CalibrationFailure("no cap below 10000")).build();
		requireThat(Scheduler.plan(blocked, NOW, 8), "blocked").isEmpty();
	}

	/**
	 * Verifies that baseline trials run the incumbent on the capacity left after the working candidate's window,
	 * up to the test's target.
	 */
	@Test
	public void baselineTrialsUseRemainingCapacity()
	{
		// Requirement: R-D7
		Campaign campaign = new Builder().confirmed().working(1).development("formatting", 10, 0).
			development("plain_list", 10, 0).development("safety", 10, 0).baseline("formatting", 50, 0).
			baseline("plain_list", 50, 0).baseline("safety", 47, 0).build();
		List<Admission> plan = Scheduler.plan(campaign, NOW, 8);
		List<Admission> baseline = new ArrayList<>();
		for (Admission admission : plan)
		{
			if (admission.purpose() == Purpose.BASELINE)
				baseline.add(admission);
		}
		Admission expected = new Admission.Assign(Purpose.BASELINE, 1, Builder.INCUMBENT, "safety", null,
			new Allocation(AllocationRule.BASELINE, 0, NOW));
		requireThat(baseline, "baseline").isEqualTo(List.of(expected, expected, expected));
		requireThat(plan.size(), "size").isEqualTo(4);
	}

	/**
	 * Verifies that baseline trials stop once every target is met, and that they never run while the incumbent is
	 * the working candidate.
	 */
	@Test
	public void baselineTrialsStopAtTarget()
	{
		// Requirement: R-D7
		Campaign met = new Builder().confirmed().working(1).development("formatting", 10, 0).
			development("safety", 10, 0).readyBaseline().build();
		for (Admission admission : Scheduler.plan(met, NOW, 8))
			requireThat(admission.purpose(), "met.purpose").isNotEqualTo(Purpose.BASELINE);

		Campaign incumbent = new Builder().confirmed().development("formatting", 10, 0).
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
		Campaign campaign = new Builder().confirmed().working(1).development("formatting", 10, 0).
			development("plain_list", 10, 0).development("safety", 10, 0).baseline("formatting", 50, 0).
			baseline("plain_list", 50, 0).baseline("safety", 48, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.BASELINE, "safety");
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(TransitionSteps.last(campaign)));
		List<Admission> baseline = new ArrayList<>();
		for (Admission admission : Scheduler.plan(campaign, NOW, 8))
		{
			if (admission.purpose() == Purpose.BASELINE)
				baseline.add(admission);
		}
		requireThat(baseline, "baseline").size().isEqualTo(1);
	}
}
