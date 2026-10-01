package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link ReadinessGate} and the batches of {@link CategoryCounts}.
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
	private static CategoryCounts counts(int passes, Integer... batches)
	{
		int failures = 0;
		for (int batch : batches)
			failures += batch;
		return new CategoryCounts(passes, failures, List.of(batches));
	}

	/**
	 * Verifies that outcomes fill batches of 50 and that the 51st outcome starts a new batch.
	 */
	@Test
	public void outcomesFillBatchesOfFifty()
	{
		// Requirement: R-D1
		CategoryCounts counts = CategoryCounts.EMPTY.add(Verdict.FAIL);
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
	public void gateRequiresEveryCategoryAndBaseline()
	{
		// Requirement: R-D1
		requireThat(ReadinessGate.isMet(new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			build()), "ready").isTrue();
		requireThat(ReadinessGate.isMet(new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			development("safety", 99, 0).build()), "categoryShort").isFalse();
		requireThat(ReadinessGate.isMet(new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			baseline("safety", 49, 0).build()), "baselineShort").isFalse();
		requireThat(ReadinessGate.isMet(new CampaignBuilder().development("formatting", 100, 0).
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
		Diagnosis diagnosis = new Diagnosis(List.of(CampaignBuilder.targeted("safety", "logs")), 0, null, false);
		requireThat(ReadinessGate.isMet(new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			diagnosis(diagnosis).build()), "diagnosis").isFalse();
		requireThat(ReadinessGate.isMet(new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			round(new Round(Map.of("safety", 0))).build()), "round").isFalse();
		requireThat(ReadinessGate.isMet(new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			regression(CampaignBuilder.targeted("safety", "logs"), Verdict.FAIL).build()), "regression").isFalse();
		requireThat(ReadinessGate.isMet(new CampaignBuilder().confirmed().readyDevelopment().readyBaseline().
			regression(CampaignBuilder.targeted("safety", "logs"), Verdict.PASS).build()), "fixedRegression").
			isTrue();
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
	private static CampaignBuilder ready()
	{
		return new CampaignBuilder().confirmed().readyDevelopment().readyBaseline();
	}
}
