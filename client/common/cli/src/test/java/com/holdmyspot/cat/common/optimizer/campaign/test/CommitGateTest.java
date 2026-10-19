package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.Diagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.CommitGate;
import com.holdmyspot.cat.common.optimizer.campaign.ReproductionRound;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link CommitGate} and the batches of {@link CellCounts}.
 */
public final class CommitGateTest
{
	/**
	 * Creates the test.
	 */
	public CommitGateTest()
	{
	}

	/**
	 * Returns counts with explicit batches.
	 *
	 * @param passes the passes
	 * @param batches the failures of each started batch
	 * @return the counts
	 */
	private static CellCounts counts(int passes, Integer... batches)
	{
		int failures = 0;
		for (int batch : batches)
			failures += batch;
		return new CellCounts(passes, failures, List.of(batches));
	}

	/**
	 * Verifies that outcomes fill batches of 50 and that the 51st outcome starts a new batch.
	 */
	@Test
	public void outcomesFillBatchesOfFifty()
	{
		// Requirement: R-D1
		CellCounts counts = CellCounts.EMPTY.add(Verdict.FAIL);
		requireThat(counts, "first").isEqualTo(counts(0, 1));
		for (int i = 1; i < 50; ++i)
			counts = counts.add(Verdict.PASS);
		requireThat(counts, "fifty").isEqualTo(counts(49, 1));
		requireThat(counts.completedBatchFailures(), "completed").containsExactly(List.of(1));
		counts = counts.add(Verdict.FAIL);
		requireThat(counts, "fiftyOne").isEqualTo(counts(49, 1, 1));
		requireThat(counts.completedBatchFailures(), "completedAfter").containsExactly(List.of(1));
	}

	/**
	 * Verifies the minimum of 100 trials on both sides.
	 */
	@Test
	public void requiresOneHundredTrials()
	{
		// Requirement: R-D1
		requireThat(CommitGate.isMetInCell(counts(99, 0, 0), Contract.DEFAULT_MINIMUM_PASS_RATE), "99").isFalse();
		requireThat(CommitGate.isMetInCell(counts(100, 0, 0), Contract.DEFAULT_MINIMUM_PASS_RATE), "100").isTrue();
	}

	/**
	 * Verifies the 99% pass rate on both sides.
	 */
	@Test
	public void requiresNinetyNinePercent()
	{
		// Requirement: R-D1
		requireThat(CommitGate.isMetInCell(counts(99, 1, 0), Contract.DEFAULT_MINIMUM_PASS_RATE), "99of100").isTrue();
		requireThat(CommitGate.isMetInCell(counts(98, 1, 1), Contract.DEFAULT_MINIMUM_PASS_RATE), "98of100").isFalse();
		requireThat(CommitGate.isMetInCell(counts(297, 1, 1, 1, 0, 0, 0), Contract.DEFAULT_MINIMUM_PASS_RATE),
			"297of300").isTrue();
		requireThat(CommitGate.isMetInCell(counts(296, 1, 1, 1, 1, 0, 0), Contract.DEFAULT_MINIMUM_PASS_RATE),
			"296of300").isFalse();
	}

	/**
	 * Verifies that each of the latest two completed batches allows 1 failure and not 2, and that an incomplete
	 * batch does not count.
	 */
	@Test
	public void requiresLatestTwoBatches()
	{
		// Requirement: R-D1
		requireThat(CommitGate.isMetInCell(counts(298, 0, 0, 0, 0, 1, 1), Contract.DEFAULT_MINIMUM_PASS_RATE),
			"oneEach").isTrue();
		requireThat(CommitGate.isMetInCell(counts(298, 0, 0, 0, 0, 0, 2), Contract.DEFAULT_MINIMUM_PASS_RATE),
			"twoInLatest").isFalse();
		requireThat(CommitGate.isMetInCell(counts(298, 0, 0, 0, 0, 2, 0), Contract.DEFAULT_MINIMUM_PASS_RATE),
			"twoInPrevious").isFalse();
		requireThat(CommitGate.isMetInCell(counts(298, 2, 0, 0, 0, 0, 0), Contract.DEFAULT_MINIMUM_PASS_RATE),
			"twoInEarlier").isTrue();
		requireThat(CommitGate.isMetInCell(counts(318, 0, 0, 0, 0, 0, 0, 2), Contract.DEFAULT_MINIMUM_PASS_RATE),
			"twoInIncomplete").isTrue();
	}

	/**
	 * Verifies that the gate holds only when every test meets it and the baseline is met.
	 */
	@Test
	public void gateRequiresEveryCellAndBaseline()
	{
		// Requirement: R-D1
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			build()), "met").isTrue();
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			development("safety", 99, 0).build()), "testShort").isFalse();
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			baseline("safety", 49, 0).build()), "baselineShort").isFalse();
		requireThat(CommitGate.isMet(new Builder().development("formatting", 100, 0).
			development("safety", 100, 0).build()), "unconfirmed").
			isFalse();
	}

	/**
	 * Verifies that an unresolved reproducible defect or a known regression blocks the gate.
	 */
	@Test
	public void gateRequiresNoUnresolvedDefect()
	{
		// Requirement: R-D1
		Diagnosis diagnosis = new Diagnosis(List.of(Builder.targeted("safety", "logs")), 0, null, false);
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			diagnosis(diagnosis).build()), "diagnosis").isFalse();
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			round(new Round(Map.of("safety", 0))).build()), "round").isFalse();
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			regression(Builder.targeted("safety", "logs"), Verdict.FAIL).build()), "regression").isFalse();
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			regression(Builder.targeted("safety", "logs"), Verdict.PASS).build()), "fixedRegression").
			isTrue();
	}

	/**
	 * Verifies that a regression test case whose failing working-candidate outcome was settled by a round that ended
	 * without a promotable attempt does not block the gate, while the same unsettled failure does.
	 */
	@Test
	public void settledRegressionDoesNotBlockGate()
	{
		DrawnTestCase logs = Builder.targeted("safety", "logs");
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			settledRegression(logs).build()), "settled").isTrue();
		requireThat(CommitGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			regression(logs, Verdict.FAIL).build()), "unsettled").isFalse();
	}

	/**
	 * Verifies that the gate does not hold while a failure group of the latest certification attempt is reproducing,
	 * and holds again once the group is reproduced or the user chose a new attempt without reproducing it.
	 */
	@Test
	public void gateWaitsForReproduction()
	{
		// Requirement: R-C19
		DrawnTestCase testCase = Builder.testCase("safety", "logs");
		FailedAssertion failed = new FailedAssertion("uses_requested_tone", "wrong_tone");
		Builder.AttemptBuilder attempt = ready().certificationAttempt(1).
			failure(new Failure(testCase, "failure", List.of(failed), null, List.of(), List.of(), Builder.FILES)).
			outcome(Outcome.BELOW_MINIMUM);
		requireThat(CommitGate.isMet(attempt.build()), "reproducing").isFalse();
		for (FailureGroup.Status status : List.of(FailureGroup.Status.REPRODUCED, FailureGroup.Status.ABANDONED))
		{
			FailureGroup group = new FailureGroup("safety", testCase.testId(), failed, 1, FailureGroup.ROUNDS,
				List.of(new ReproductionRound("safety", List.of())), status);
			requireThat(CommitGate.isMet(attempt.groups(List.of(group)).build()), status.name()).isTrue();
		}
	}

	/**
	 * Verifies that a commit is due only when the gate holds and nothing blocks admission.
	 */
	@Test
	public void commitDueOnlyWhenUnblocked()
	{
		// Requirement: R-D1
		requireThat(CommitGate.isCommitDue(ready().build()), "met").isTrue();
		requireThat(CommitGate.isCommitDue(ready().pending(new PendingDecision.UnownedChange("edit")).build()),
			"pending").isFalse();
		requireThat(CommitGate.isCommitDue(ready().revisionRequested().build()), "revision").isFalse();
		requireThat(CommitGate.isCommitDue(ready().lifecycle(Lifecycle.CANCELLED).build()), "cancelled").
			isFalse();
		requireThat(CommitGate.isCommitDue(ready().certificationAttempt(1).build()), "running").isFalse();
		requireThat(CommitGate.isCommitDue(ready().certificationAttempt(1).outcome(Outcome.INCONCLUSIVE).
			build()), "ended").isTrue();
		requireThat(CommitGate.isCommitDue(ready().unavailable(Outcome.UNSUPPORTED_OUTPUT).build()),
			"unavailable").isFalse();
	}

	/**
	 * Returns a builder of a campaign whose commit is due.
	 *
	 * @return the builder
	 */
	private static Builder ready()
	{
		return new Builder().confirmed().readyDevelopment().readyBaseline();
	}

	/**
	 * Verifies that a test under two profiles needs each of its cells to meet the gate and each cell's baseline to be
	 * met.
	 */
	@Test
	public void gateIsPerCell()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		requireThat(CommitGate.isMet(new Builder().confirmed(contract, 1).readyDevelopment().
			readyBaseline().build()), "met").isTrue();
		requireThat(CommitGate.isMet(new Builder().confirmed(contract, 1).readyDevelopment().
			readyBaseline().development(ContractFixtures.SMALL_CELL, 99, 0).build()), "smallCellShort").isFalse();
		requireThat(CommitGate.isMet(new Builder().confirmed(contract, 1).readyDevelopment().
			readyBaseline().baseline(ContractFixtures.BIG_CELL, 49, 0).build()), "bigBaselineShort").isFalse();
	}

	/**
	 * Verifies that the gate applies to the development cells only: an empty cell outside them does not
	 * block it, and a cell where a certification attempt of the lineage adopted a failure must meet it too.
	 */
	@Test
	public void gateCoversDevelopmentCells()
	{
		// Requirement: R-D1
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		Builder weakest = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(TierFixtures.SMALL_HIGH, 100, 0).development(TierFixtures.SAFETY, 100, 0);
		requireThat(CommitGate.isMet(weakest.build()), "weakestReady").isTrue();

		DrawnTestCase failure = new Generator(contract).draw(TierFixtures.BIG_HIGH,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		Campaign failed = weakest.certificationAttempt(1).failure(failure).outcome(Outcome.INCONCLUSIVE).build();
		requireThat(CommitGate.isMet(failed), "certificationFailure").isFalse();
	}

	/**
	 * Verifies the count conditions at the minimum pass rate {@code p_min = 0.90}: a failure rate of at most 2% and at
	 * most 2 failures in each of the latest two complete batches.
	 */
	@Test
	public void countConditionsFollowMinimumPassRate()
	{
		// Requirement: R-D1
		BigDecimal ninety = new BigDecimal("0.90");
		requireThat(CommitGate.isMetInCell(counts(98, 1, 1), ninety), "98of100").isTrue();
		requireThat(CommitGate.isMetInCell(counts(97, 2, 1), ninety), "97of100").isFalse();
		requireThat(CommitGate.isMetInCell(counts(98, 0, 2), ninety), "twoInLatest").isTrue();
		requireThat(CommitGate.isMetInCell(counts(297, 0, 0, 0, 0, 0, 3), ninety), "threeInLatest").isFalse();
		requireThat(CommitGate.minimumObservedPassRate(ninety), "minimumObservedPassRate").
			isEqualTo(new BigDecimal("0.98"));
		requireThat(CommitGate.batchFailureLimit(ninety), "batchFailureLimit").isEqualTo(2);
	}

	/**
	 * Verifies that at the minimum pass rate {@code p_min = 0.97} a complete batch may hold no failure, because
	 * {@code 2(1 - p_min)/5} of 50 is below 1.
	 */
	@Test
	public void strictMinimumAllowsNoBatchFailure()
	{
		// Requirement: R-D1
		BigDecimal ninetySeven = new BigDecimal("0.97");
		requireThat(CommitGate.isMetInCell(counts(299, 0, 0, 0, 1, 0, 0), ninetySeven), "earlierFailure").isTrue();
		requireThat(CommitGate.isMetInCell(counts(299, 0, 0, 0, 0, 1, 0), ninetySeven), "previousFailure").isFalse();
		requireThat(CommitGate.batchFailureLimit(ninetySeven), "batchFailureLimit").isEqualTo(0);
	}
}
