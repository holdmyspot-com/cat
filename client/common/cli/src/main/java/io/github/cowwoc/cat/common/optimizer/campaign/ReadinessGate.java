package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


import java.util.List;

/**
 * The commit condition of {@code design.md} section 2.7. It is a cost control, not evidence.
 */
public final class ReadinessGate
{
	/**
	 * The fewest representative development trials that a ready cell has.
	 */
	public static final int MINIMUM_TRIALS = 100;
	/**
	 * The most failures that each of the latest two complete batches of a ready cell has.
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
	public static boolean isReady(CategoryCounts counts)
	{
		requireThat(counts, "counts").isNotNull();
		int outcomes = counts.outcomes();
		if (outcomes < MINIMUM_TRIALS || 100L * counts.passes() < 99L * outcomes)
			return false;
		List<Integer> completed = counts.completedBatchFailures();
		if (completed.size() < 2)
			return false;
		return completed.getLast() <= BATCH_FAILURE_LIMIT &&
			completed.get(completed.size() - 2) <= BATCH_FAILURE_LIMIT;
	}

	/**
	 * Indicates whether the working candidate meets the gate: every cell is ready, no diagnosis, round or author
	 * attempt is unresolved, no regression instance is known to fail for the working candidate, and the incumbent
	 * baseline is met.
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
		for (String cell : campaign.contract().cellIds())
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
