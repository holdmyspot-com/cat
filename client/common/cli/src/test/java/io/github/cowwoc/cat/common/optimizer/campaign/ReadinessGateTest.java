package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;

import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link ReadinessGate} and the batches of {@link CellCounts}.
 */
public final class ReadinessGateTest
{
	/**
	 * Creates the test.
	 */
	public ReadinessGateTest()
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
		requireThat(ReadinessGate.isReady(counts(99, 0, 0)), "99").isFalse();
		requireThat(ReadinessGate.isReady(counts(100, 0, 0)), "100").isTrue();
	}

	/**
	 * Verifies the 99% pass rate on both sides.
	 */
	@Test
	public void requiresNinetyNinePercent()
	{
		// Requirement: R-D1
		requireThat(ReadinessGate.isReady(counts(99, 1, 0)), "99of100").isTrue();
		requireThat(ReadinessGate.isReady(counts(98, 1, 1)), "98of100").isFalse();
		requireThat(ReadinessGate.isReady(counts(297, 1, 1, 1, 0, 0, 0)), "297of300").isTrue();
		requireThat(ReadinessGate.isReady(counts(296, 1, 1, 1, 1, 0, 0)), "296of300").isFalse();
	}

	/**
	 * Verifies that each of the latest two completed batches allows 1 failure and not 2, and that an incomplete
	 * batch does not count.
	 */
	@Test
	public void requiresLatestTwoBatches()
	{
		// Requirement: R-D1
		requireThat(ReadinessGate.isReady(counts(298, 0, 0, 0, 0, 1, 1)), "oneEach").isTrue();
		requireThat(ReadinessGate.isReady(counts(298, 0, 0, 0, 0, 0, 2)), "twoInLatest").isFalse();
		requireThat(ReadinessGate.isReady(counts(298, 0, 0, 0, 0, 2, 0)), "twoInPrevious").isFalse();
		requireThat(ReadinessGate.isReady(counts(298, 2, 0, 0, 0, 0, 0)), "twoInEarlier").isTrue();
		requireThat(ReadinessGate.isReady(counts(318, 0, 0, 0, 0, 0, 0, 2)), "twoInIncomplete").isTrue();
	}

	/**
	 * Verifies that the gate holds only when every category is ready and the baseline is met.
	 */
	@Test
	public void gateRequiresEveryCellAndBaseline()
	{
		// Requirement: R-D1
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			build()), "ready").isTrue();
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			development("safety", 99, 0).build()), "categoryShort").isFalse();
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			baseline("safety", 49, 0).build()), "baselineShort").isFalse();
		requireThat(ReadinessGate.isMet(new Builder().development("formatting", 100, 0).
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
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			diagnosis(diagnosis).build()), "diagnosis").isFalse();
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			round(new Round(Map.of("safety", 0))).build()), "round").isFalse();
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			regression(Builder.targeted("safety", "logs"), Verdict.FAIL).build()), "regression").isFalse();
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			regression(Builder.targeted("safety", "logs"), Verdict.PASS).build()), "fixedRegression").
			isTrue();
	}

	/**
	 * Verifies that a regression instance whose failing working-candidate outcome was settled by a round that ended
	 * without a promotable attempt does not block the gate, while the same unsettled failure does.
	 */
	@Test
	public void settledRegressionDoesNotBlockGate()
	{
		TrialInstance logs = Builder.targeted("safety", "logs");
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
			settledRegression(logs).build()), "settled").isTrue();
		requireThat(ReadinessGate.isMet(new Builder().confirmed().readyDevelopment().readyBaseline().
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
		TrialInstance instance = Builder.instance("safety", "logs");
		FailedRequirement failed = new FailedRequirement("r1", "wrong_tone");
		Builder.AttemptBuilder attempt = ready().certificationAttempt(1).
			failure(new Failure(instance, "failure", List.of(failed), null, List.of(), List.of(), Builder.FILES)).
			outcome(Outcome.BELOW_MINIMUM);
		requireThat(ReadinessGate.isMet(attempt.build()), "reproducing").isFalse();
		for (FailureGroup.Status status : List.of(FailureGroup.Status.REPRODUCED, FailureGroup.Status.ABANDONED))
		{
			FailureGroup group = new FailureGroup("safety", instance.testId(), failed, 1, FailureGroup.ROUNDS,
				List.of(new ReproductionRound("safety", List.of())), status);
			requireThat(ReadinessGate.isMet(attempt.groups(List.of(group)).build()), status.name()).isTrue();
		}
	}

	/**
	 * Verifies that a commit is due only when the gate holds and nothing blocks admission.
	 */
	@Test
	public void commitDueOnlyWhenUnblocked()
	{
		// Requirement: R-D1
		requireThat(ReadinessGate.isCommitDue(ready().build()), "ready").isTrue();
		requireThat(ReadinessGate.isCommitDue(ready().pending(new PendingDecision.UnownedChange("edit")).build()),
			"pending").isFalse();
		requireThat(ReadinessGate.isCommitDue(ready().revisionRequested().build()), "revision").isFalse();
		requireThat(ReadinessGate.isCommitDue(ready().lifecycle(Lifecycle.CANCELLED).build()), "cancelled").
			isFalse();
		requireThat(ReadinessGate.isCommitDue(ready().certificationAttempt(1).build()), "running").isFalse();
		requireThat(ReadinessGate.isCommitDue(ready().certificationAttempt(1).outcome(Outcome.INCONCLUSIVE).
			build()), "ended").isTrue();
		requireThat(ReadinessGate.isCommitDue(ready().unavailable(Outcome.UNSUPPORTED_OUTPUT).build()),
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
	 * Verifies that a category under two profiles needs each of its cells to be ready and each cell's baseline to be
	 * met.
	 */
	@Test
	public void gateIsPerCell()
	{
		// Requirement: R-C11
		Contract contract = ContractFixtures.twoCellContract();
		requireThat(ReadinessGate.isMet(new Builder().confirmed(contract, 1).readyDevelopment().
			readyBaseline().build()), "ready").isTrue();
		requireThat(ReadinessGate.isMet(new Builder().confirmed(contract, 1).readyDevelopment().
			readyBaseline().development(ContractFixtures.SMALL_CELL, 99, 0).build()), "smallCellShort").isFalse();
		requireThat(ReadinessGate.isMet(new Builder().confirmed(contract, 1).readyDevelopment().
			readyBaseline().baseline(ContractFixtures.BIG_CELL, 49, 0).build()), "bigBaselineShort").isFalse();
	}

	/**
	 * Verifies that the gate requires readiness in the development cells only: an empty cell outside them does not
	 * block it, and a cell where a certification attempt of the lineage adopted a failure must be ready too.
	 */
	@Test
	public void gateCoversDevelopmentCells()
	{
		// Requirement: R-D1
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		Builder weakest = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(TierFixtures.SMALL_HIGH, 100, 0).development(TierFixtures.SAFETY, 100, 0);
		requireThat(ReadinessGate.isMet(weakest.build()), "weakestReady").isTrue();

		TrialInstance failure = new Generator(contract).draw(TierFixtures.BIG_HIGH,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		Campaign failed = weakest.certificationAttempt(1).failure(failure).outcome(Outcome.INCONCLUSIVE).build();
		requireThat(ReadinessGate.isMet(failed), "certificationFailure").isFalse();
	}
}
