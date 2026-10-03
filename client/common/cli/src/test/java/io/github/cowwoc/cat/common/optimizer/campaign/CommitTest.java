package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.statistics.Comparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests the commit that freezes a certification attempt.
 */
public final class CommitTest
{
	/**
	 * Creates the test.
	 */
	public CommitTest()
	{
	}

	/**
	 * Returns a builder for a campaign ready for certification, with working candidate 1 and a baseline of 50 passes in
	 * {@code formatting} and 48 passes and 2 failures in {@code safety}.
	 *
	 * @return the builder
	 */
	private static Builder ready()
	{
		return new Builder().confirmed().working(1).readyDevelopment().baseline("formatting", 50, 0).
			baseline("safety", 48, 2);
	}

	/**
	 * Returns the calibration of a certification index.
	 *
	 * @param index the certification index
	 * @return a calibration with the index's level and a cap of 262
	 */
	private static CapCalibration calibration(int index)
	{
		return new CapCalibration(ErrorBudget.certificationLevel(index), 262);
	}

	/**
	 * Verifies that a commit cannot freeze informative incumbent bounds when only one observation records a measurement
	 * condition.
	 */
	@Test
	public void missingConditionsDisableBounds()
	{
		// Requirement: R-M4, R-F1
		Campaign campaign = ready().identity(MeasurementIdentity.EMPTY).
			baselineIdentity(Builder.confirmedIdentity("model-a")).build();
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(calibration(1), 1));
		CertificationProcedure procedure = campaign.attempt(1).procedure();
		Comparison unavailable = new Comparison(BigDecimal.ONE, BigDecimal.ZERO, procedure.improvementLevel());

		for (ComparisonBoundaries boundary : procedure.boundaries().comparisons())
			requireThat(boundary.comparison(), "comparison").isEqualTo(unavailable);
	}

	/**
	 * Verifies that a commit cannot freeze informative incumbent bounds under a differing model.
	 */
	@Test
	public void differingConditionsDisableBounds()
	{
		// Requirement: R-M4, R-F1
		MeasurementIdentity baselineIdentity = Builder.confirmedIdentity("model-b");
		Campaign campaign = ready().baselineIdentity(baselineIdentity).build();
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(calibration(1), 1));
		CertificationProcedure procedure = campaign.attempt(1).procedure();
		Comparison unavailable = new Comparison(BigDecimal.ONE, BigDecimal.ZERO, procedure.improvementLevel());

		for (ComparisonBoundaries boundary : procedure.boundaries().comparisons())
			requireThat(boundary.comparison(), "comparison").isEqualTo(unavailable);
		requireThat(procedure.baselineIdentity(), "baselineIdentity").isEqualTo(baselineIdentity);
	}

	/**
	 * Verifies that a commit freezes the candidate, the levels, the incumbent bounds, the bets, the cap and the
	 * allocation policy, and opens the formal stream at its start.
	 */
	@Test
	public void commitFreezesProcedure()
	{
		// Requirement: R-F1, R-M4
		Campaign before = ready().build();
		Campaign campaign = Transitions.apply(before, new CampaignEvent.Commit(calibration(1), 41));
		CertificationAttempt attempt = campaign.attempt(1);

		requireThat(attempt.isRunning(), "isRunning").isTrue();
		requireThat(attempt.lineage(), "lineage").isEqualTo(1);
		requireThat(attempt.candidate(), "candidate").isEqualTo(before.development().working());
		CertificationProcedure procedure = attempt.procedure();
		SignificanceLevel improvement = ErrorBudget.improvementLevel(1, 2);
		requireThat(procedure.baselineIdentity(), "baselineIdentity").isEqualTo(before.baseline().identity());
		requireThat(procedure.improvementLevel(), "improvementLevel").isEqualTo(improvement);
		requireThat(procedure.baselineIndex(), "baselineIndex").isEqualTo(1);
		requireThat(procedure.cells(), "categories").isEqualTo(List.of("formatting", "safety"));
		requireThat(procedure.boundaries().level(), "level").isEqualTo(ErrorBudget.certificationLevel(1));
		requireThat(procedure.boundaries().cap(), "cap").isEqualTo(262);
		SignificanceLevel incumbent = ErrorBudget.incumbentLevel(1, 2);
		requireThat(procedure.boundaries().comparisons().get(0).comparison(), "formattingComparison").
			isEqualTo(Comparison.freeze(50, 0, incumbent, improvement));
		requireThat(procedure.boundaries().comparisons().get(1).comparison(), "safetyComparison").
			isEqualTo(Comparison.freeze(48, 2, incumbent, improvement));
		requireThat(procedure.bets(), "bets").isEqualTo(CertificationProcedure.BETS);
		requireThat(procedure.allocationPolicy(), "allocationPolicy").
			isEqualTo(CertificationProcedure.ALLOCATION_POLICY);
		requireThat(attempt.formalStream(), "formalStream").
			isEqualTo(new DrawStream.State(DrawStream.Kind.FORMAL, 41, 0, true));
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that formal draws come from the frozen attempt's stream, after the freeze.
	 */
	@Test
	public void formalDrawsFollowFreeze()
	{
		// Requirement: R-F1
		Campaign campaign = Transitions.apply(ready().build(), new CampaignEvent.Commit(calibration(1), 41));
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		TrialInstance instance = campaign.trials().getFirst().instance();
		requireThat(instance.streamKind(), "streamKind").isEqualTo(DrawStream.Kind.FORMAL);
		requireThat(instance.streamPosition(), "streamPosition").isEqualTo(0L);
		requireThat(campaign.attempt(1).formalStream().position() > 0, "advanced").isTrue();
	}

	/**
	 * Verifies that no formal trial can be assigned before a commit.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void noFormalDrawBeforeCommit()
	{
		// Requirement: R-F1
		Campaign campaign = ready().build();
		Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(Purpose.FORMAL, 1,
			campaign.development().working().snapshot(), "safety", null,
			TransitionSteps.allocation(campaign, Purpose.FORMAL))));
	}

	/**
	 * Verifies that a calibration for another certification index is rejected.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void calibrationMustMatchIndex()
	{
		// Requirement: R-F1
		Transitions.apply(ready().build(), new CampaignEvent.Commit(calibration(2), 41));
	}

	/**
	 * Verifies that a commit is refused before the readiness gate holds.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void commitRequiresReadiness()
	{
		// Requirement: R-F1
		Transitions.apply(ready().development("safety", 99, 0).build(),
			new CampaignEvent.Commit(calibration(1), 41));
	}

	/**
	 * Verifies that a running attempt cannot be frozen again.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void runningAttemptIsNotRefrozen()
	{
		// Requirement: R-F4
		Campaign campaign = Transitions.apply(ready().build(), new CampaignEvent.Commit(calibration(1), 41));
		Transitions.apply(campaign, new CampaignEvent.Commit(calibration(2), 42));
	}

	/**
	 * Verifies that no author attempt can edit the candidate while its certification attempt runs.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void noEditDuringAttempt()
	{
		// Requirement: R-F4
		Campaign campaign = Transitions.apply(ready().build(), new CampaignEvent.Commit(calibration(1), 41));
		TransitionSteps.submit(campaign, "safety",
			Builder.snapshot("edit"), List.of());
	}

	/**
	 * Verifies that formal outcomes never revise the frozen candidate: the edit that they inform becomes a new
	 * candidate that needs a new attempt with the next index.
	 */
	// Requirement: R-D3
	@Test
	public void editNeedsNewAttempt()
	{
		// Requirement: R-F4
		Campaign campaign = ready().build();
		Candidate frozen = campaign.development().working();
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(calibration(1), 41));
		for (int i = 0; i < 50 && campaign.attempt(1).isRunning(); ++i)
			campaign = TransitionSteps.adopt(campaign, Purpose.FORMAL, "safety", Verdict.FAIL);
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
		requireThat(campaign.development().round(), "round").isNotNull();

		Sha256 edit = Builder.snapshot("edit");
		campaign = TransitionSteps.submit(campaign, "safety", edit, List.of());
		while (campaign.development().author().runs().stream().anyMatch(run -> run.verdict() == null))
		{
			AuthorAttempt author = campaign.development().author();
			PromotionRun run = author.runs().stream().filter(element -> element.verdict() == null).
				findFirst().orElseThrow();
			Sha256 snapshot = edit;
			if (run.role() == PromotionRun.Role.WORKING_REFERENCE)
				snapshot = frozen.snapshot();
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, author.number(), snapshot, run.instance(),
				Verdict.PASS);
		}
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(edit));
		requireThat(campaign.attempt(1).candidate(), "frozen").isEqualTo(frozen);
		requireThat(campaign.development().working(), "working").isEqualTo(new Candidate(2, edit));
		requireThat(campaign.nextCertificationIndex(), "nextCertificationIndex").isEqualTo(2);
	}

	/**
	 * Verifies that a commit freezes one comparison per cell from that cell's baseline, divides the improvement and
	 * incumbent budgets by the number of cells, and waits until every cell is ready.
	 */
	@Test
	public void commitFreezesEachCell()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		Builder ready = new Builder().confirmed(contract, 1).working(1).readyDevelopment().
			baseline(ContractFixtures.BIG_CELL, 50, 0).baseline(ContractFixtures.SMALL_CELL, 48, 2).
			baseline("safety", 50, 0);
		Campaign campaign = Transitions.apply(ready.build(), new CampaignEvent.Commit(calibration(1), 41));
		CertificationProcedure procedure = campaign.attempt(1).procedure();

		requireThat(procedure.cells(), "cells").isEqualTo(List.of(ContractFixtures.BIG_CELL,
			ContractFixtures.SMALL_CELL, "safety"));
		SignificanceLevel improvement = ErrorBudget.improvementLevel(1, 3);
		SignificanceLevel incumbent = ErrorBudget.incumbentLevel(1, 3);
		requireThat(procedure.improvementLevel(), "improvementLevel").isEqualTo(improvement);
		requireThat(procedure.boundaries().comparisons().get(0).comparison(), "bigComparison").
			isEqualTo(Comparison.freeze(50, 0, incumbent, improvement));
		requireThat(procedure.boundaries().comparisons().get(1).comparison(), "smallComparison").
			isEqualTo(Comparison.freeze(48, 2, incumbent, improvement));

		Campaign smallShort = ready.development(ContractFixtures.SMALL_CELL, 99, 0).build();
		requireThat(ReadinessGate.isCommitDue(smallShort), "smallShort").isFalse();
	}
}
