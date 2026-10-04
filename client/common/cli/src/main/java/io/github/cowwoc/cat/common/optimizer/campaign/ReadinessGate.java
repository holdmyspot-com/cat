package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.List;

/**
 * Checks whether development is ready to freeze the working candidate and start certification.
 * <p>
 * Each {@link CellTier development cell}, a requirement category under one runner profile, must meet the development
 * thresholds below. Passing these checks controls when certification starts; it does not certify the candidate.
 */
public final class ReadinessGate
{
	/**
	 * Require at least this many representative development trials before treating a cell as ready.
	 */
	public static final int MINIMUM_TRIALS = 100;
	/**
	 * Treat a cell as unready if either of its latest two complete batches exceeds this many failures.
	 */
	public static final int BATCH_FAILURE_LIMIT = 1;

	/**
	 * Prevents instantiation.
	 */
	private ReadinessGate()
	{
	}

	/**
	 * Indicates whether a cell's development counts meet the gate: at least {@value #MINIMUM_TRIALS} trials, at
	 * least 99% passes, and at least 98% passes in each of the latest two complete batches.
	 *
	 * @param counts the cell's development counts
	 * @return {@code true} if the cell is ready
	 * @throws NullPointerException if {@code counts} is null
	 */
	public static boolean isReady(CellCounts counts)
	{
		requireThat(counts, "counts").isNotNull();
		int outcomes = counts.outcomes();
		if (outcomes < MINIMUM_TRIALS || !meetsPassRateThreshold(counts))
			return false;
		List<Integer> completed = counts.completedBatchFailures();
		if (completed.size() < 2)
			return false;
		return completed.getLast() <= BATCH_FAILURE_LIMIT &&
			completed.get(completed.size() - 2) <= BATCH_FAILURE_LIMIT;
	}

	/**
	 * Indicates whether at least 99% of a cell's development outcomes are passes. Empty counts meet this rate
	 * check; the readiness gate separately requires a minimum number of outcomes.
	 *
	 * @param counts the cell's development counts
	 * @return {@code true} if the cell meets the pass-rate threshold
	 * @throws NullPointerException if {@code counts} is null
	 */
	public static boolean meetsPassRateThreshold(CellCounts counts)
	{
		requireThat(counts, "counts").isNotNull();
		return 100L * counts.passes() >= 99L * counts.outcomes();
	}

	/**
	 * Indicates whether the working candidate meets the gate: every development cell is ready, no diagnosis, round or
	 * author attempt is unresolved, no regression instance is known to fail for the working candidate, and the
	 * incumbent baseline is met.
	 *
	 * @param campaign the campaign
	 * @return {@code true} if the gate is met
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static boolean isMet(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (!campaign.isConfirmed())
			return false;
		Development development = campaign.development();
		if (development.isPaused())
			return false;
		for (String cell : CellTier.developmentCellIds(campaign))
		{
			if (!isReady(development.count(cell)))
				return false;
		}
		for (RegressionInstance regression : campaign.lineage().regressionSet())
		{
			if (regression.working() == Verdict.FAIL)
				return false;
		}
		return Baseline.isMet(campaign.baseline(), campaign.contract());
	}

	/**
	 * Indicates whether the driver should commit the working candidate now: the campaign is active, waits for no
	 * decision or revision, runs no attempt, knows of no condition that makes certification unavailable, and meets
	 * the gate.
	 *
	 * @param campaign the campaign
	 * @return {@code true} if a commit is due
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static boolean isCommitDue(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		if (campaign.lifecycle() != Lifecycle.ACTIVE || campaign.pending() != null || campaign.revisionRequested() ||
			campaign.runningAttempt().isPresent())
		{
			return false;
		}
		if (campaign.isConfirmed() && !campaign.lineage().unavailable().isEmpty())
			return false;
		return isMet(campaign);
	}
}
