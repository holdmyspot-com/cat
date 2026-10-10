package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.Impact;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.ReadinessGate;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Triage;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures;
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
	 * {@code formatting} and in {@code plain_list}, and 48 passes and 2 failures in {@code safety}.
	 *
	 * @return the builder
	 */
	private static Builder ready()
	{
		return new Builder().confirmed().working(1).readyDevelopment().baseline("formatting", 50, 0).
			baseline("plain_list", 50, 0).baseline("safety", 48, 2);
	}

	/**
	 * Returns the calibration of a certification index.
	 *
	 * @param index the certification index
	 * @return a calibration with the index's level and a cap of 262
	 */
	private static CapCalibration calibration(int index)
	{
		return new CapCalibration(ErrorBudget.certificationLevel(index), Contract.DEFAULT_MINIMUM_PASS_RATE, 262);
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
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(calibration(1), 1, Builder.CONTROL));
		CertificationProcedure procedure = campaign.attempt(1).procedure();
		Comparison unavailable = new Comparison(BigDecimal.ONE, BigDecimal.ZERO, procedure.improvementLevel(),
			Contract.DEFAULT_MINIMUM_PASS_RATE);

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
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(calibration(1), 1, Builder.CONTROL));
		CertificationProcedure procedure = campaign.attempt(1).procedure();
		Comparison unavailable = new Comparison(BigDecimal.ONE, BigDecimal.ZERO, procedure.improvementLevel(),
			Contract.DEFAULT_MINIMUM_PASS_RATE);

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
		Campaign campaign = Transitions.apply(before, new CampaignEvent.Commit(calibration(1), 41, Builder.CONTROL));
		CertificationAttempt attempt = campaign.attempt(1);

		requireThat(attempt.isRunning(), "isRunning").isTrue();
		requireThat(attempt.lineage(), "lineage").isEqualTo(1);
		requireThat(attempt.candidate(), "candidate").isEqualTo(before.development().working());
		CertificationProcedure procedure = attempt.procedure();
		SignificanceLevel improvement = ErrorBudget.improvementLevel(1, 3);
		requireThat(procedure.baselineIdentity(), "baselineIdentity").isEqualTo(before.baseline().identity());
		requireThat(procedure.improvementLevel(), "improvementLevel").isEqualTo(improvement);
		requireThat(procedure.baselineIndex(), "baselineIndex").isEqualTo(1);
		requireThat(procedure.cells(), "cells").isEqualTo(List.of("formatting", "plain_list", "safety"));
		requireThat(procedure.boundaries().level(), "level").isEqualTo(ErrorBudget.certificationLevel(1));
		requireThat(procedure.boundaries().cap(), "cap").isEqualTo(262);
		SignificanceLevel incumbent = ErrorBudget.incumbentLevel(1, 3);
		requireThat(procedure.boundaries().comparisons().get(0).comparison(), "formattingComparison").
			isEqualTo(Comparison.freeze(50, 0, incumbent, improvement, Contract.DEFAULT_MINIMUM_PASS_RATE));
		requireThat(procedure.boundaries().comparisons().get(1).comparison(), "plainListComparison").
			isEqualTo(Comparison.freeze(50, 0, incumbent, improvement, Contract.DEFAULT_MINIMUM_PASS_RATE));
		requireThat(procedure.boundaries().comparisons().get(2).comparison(), "safetyComparison").
			isEqualTo(Comparison.freeze(48, 2, incumbent, improvement, Contract.DEFAULT_MINIMUM_PASS_RATE));
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
		Campaign campaign = Transitions.apply(ready().build(),
			new CampaignEvent.Commit(calibration(1), 41, Builder.CONTROL));
		campaign = TransitionSteps.assign(campaign, Purpose.FORMAL, "safety");
		DrawnTestCase testCase = campaign.trials().getFirst().testCase();
		requireThat(testCase.streamKind(), "streamKind").isEqualTo(DrawStream.Kind.FORMAL);
		requireThat(testCase.streamPosition(), "streamPosition").isEqualTo(0L);
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
		Transitions.apply(ready().build(), new CampaignEvent.Commit(calibration(2), 41, Builder.CONTROL));
	}

	/**
	 * Verifies that a commit is refused before the readiness gate holds.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void commitRequiresReadiness()
	{
		// Requirement: R-F1
		Transitions.apply(ready().development("safety", 99, 0).build(),
			new CampaignEvent.Commit(calibration(1), 41, Builder.CONTROL));
	}

	/**
	 * Verifies that a running attempt cannot be frozen again.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void runningAttemptIsNotRefrozen()
	{
		// Requirement: R-F4
		Campaign campaign = Transitions.apply(ready().build(),
			new CampaignEvent.Commit(calibration(1), 41, Builder.CONTROL));
		Transitions.apply(campaign, new CampaignEvent.Commit(calibration(2), 42, Builder.CONTROL));
	}

	/**
	 * Verifies that no prompt author attempt can edit the candidate while its certification attempt runs.
	 */
	@Test(expectedExceptions = IllegalStateException.class)
	public void noEditDuringAttempt()
	{
		// Requirement: R-F4
		Campaign campaign = Transitions.apply(ready().build(),
			new CampaignEvent.Commit(calibration(1), 41, Builder.CONTROL));
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
		campaign = Transitions.apply(campaign, new CampaignEvent.Commit(calibration(1), 41, Builder.CONTROL));
		for (int i = 0; i < 50 && campaign.attempt(1).isRunning(); ++i)
			campaign = TransitionSteps.adopt(campaign, Purpose.FORMAL, "safety", Verdict.FAIL);
		requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
		campaign = TransitionSteps.fail(campaign, "safety", new Triage(Impact.BLOCKING, "The reply stopped."));
		requireThat(campaign.development().round(), "round").isNotNull();

		Sha256 edit = Builder.snapshot("edit");
		campaign = TransitionSteps.submit(campaign, "safety", edit, List.of());
		while (campaign.development().promptAuthor().runs().stream().anyMatch(run -> run.verdict() == null))
		{
			PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
			PromotionRun run = promptAuthor.runs().stream().filter(element -> element.verdict() == null).
				findFirst().orElseThrow();
			Sha256 snapshot = edit;
			if (run.role() == PromotionRun.Role.WORKING_REFERENCE)
				snapshot = frozen.snapshot();
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, promptAuthor.number(), snapshot,
				run.testCase(), Verdict.PASS);
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
			baseline(ContractFixtures.PLAIN_BIG_CELL, 50, 0).baseline(ContractFixtures.PLAIN_SMALL_CELL, 50, 0).
			baseline("safety", 50, 0);
		Campaign campaign = Transitions.apply(ready.build(), new CampaignEvent.Commit(calibration(1), 41, Builder.CONTROL));
		CertificationProcedure procedure = campaign.attempt(1).procedure();

		requireThat(procedure.cells(), "cells").isEqualTo(List.of(ContractFixtures.BIG_CELL,
			ContractFixtures.SMALL_CELL, ContractFixtures.PLAIN_BIG_CELL, ContractFixtures.PLAIN_SMALL_CELL,
			"safety"));
		SignificanceLevel improvement = ErrorBudget.improvementLevel(1, 5);
		SignificanceLevel incumbent = ErrorBudget.incumbentLevel(1, 5);
		requireThat(procedure.improvementLevel(), "improvementLevel").isEqualTo(improvement);
		requireThat(procedure.boundaries().comparisons().get(0).comparison(), "bigComparison").
			isEqualTo(Comparison.freeze(50, 0, incumbent, improvement, Contract.DEFAULT_MINIMUM_PASS_RATE));
		requireThat(procedure.boundaries().comparisons().get(1).comparison(), "smallComparison").
			isEqualTo(Comparison.freeze(48, 2, incumbent, improvement, Contract.DEFAULT_MINIMUM_PASS_RATE));

		Campaign smallShort = ready.development(ContractFixtures.SMALL_CELL, 99, 0).build();
		requireThat(ReadinessGate.isCommitDue(smallShort), "smallShort").isFalse();
	}

	/**
	 * Verifies that a commit under the weakest-cell scope freezes a procedure over the scope's cells, with {@code K}
	 * the number of those cells, and needs no baseline or readiness outside them.
	 */
	@Test
	public void commitCoversScopeCells()
	{
		// Requirement: R-C15
		// Requirement: R-C11
		Contract contract = TierFixtures.tieredContract().withScope(CertificationScope.WEAKEST_CELL);
		Campaign before = new Builder().confirmed(contract, 1).working(1).
			development(TierFixtures.SMALL_HIGH, 100, 0).development(TierFixtures.SAFETY, 100, 0).
			baseline(TierFixtures.SMALL_HIGH, 50, 0).baseline(TierFixtures.SAFETY, 50, 0).build();
		Campaign campaign = Transitions.apply(before, new CampaignEvent.Commit(calibration(1), 41, Builder.CONTROL));
		CertificationProcedure procedure = campaign.attempt(1).procedure();
		requireThat(procedure.cells(), "cells").isEqualTo(List.of(TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
		requireThat(procedure.improvementLevel(), "improvementLevel").isEqualTo(ErrorBudget.improvementLevel(1, 2));
		requireThat(procedure.boundaries().comparisons().size(), "comparisons").isEqualTo(2);
	}
}
