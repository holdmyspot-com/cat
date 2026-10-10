package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checks whether development is ready to freeze the working candidate and start certification.
 * <p>
 * Each {@link CellTier development cell}, a prompt test under one runner profile, must meet the development
 * thresholds below. Passing these checks controls when certification starts; it does not certify the candidate.
 */
public final class ReadinessGate
{
	/**
	 * Require at least this many representative development trials before treating a cell as ready.
	 */
	public static final int MINIMUM_TRIALS = 100;

	/**
	 * Prevents instantiation.
	 */
	private ReadinessGate()
	{
	}

	/**
	 * Indicates whether a cell's development counts meet the gate: at least {@value #MINIMUM_TRIALS} trials, at
	 * least {@link #minimumObservedPassRate(BigDecimal)} passes, and at most {@link #batchFailureLimit(BigDecimal)}
	 * failures in each of the latest two complete batches.
	 *
	 * @param counts the cell's development counts
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return {@code true} if the cell is ready
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean isReady(CellCounts counts, BigDecimal minimumPassRate)
	{
		requireThat(counts, "counts").isNotNull();
		int outcomes = counts.outcomes();
		if (outcomes < MINIMUM_TRIALS || !meetsPassRateThreshold(counts, minimumPassRate))
			return false;
		List<Integer> completed = counts.completedBatchFailures();
		if (completed.size() < 2)
			return false;
		int limit = batchFailureLimit(minimumPassRate);
		return completed.getLast() <= limit && completed.get(completed.size() - 2) <= limit;
	}

	/**
	 * Returns the most failures that a complete batch may have for its cell to be ready, for the minimum pass rate
	 * {@code p_min}: {@code floor(20 (1 - p_min))}, which allows a {@value CellCounts#BATCH_SIZE}-trial batch two fifths
	 * of the failure rate that certification tolerates.
	 *
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return the limit
	 * @throws NullPointerException if {@code minimumPassRate} is null
	 */
	public static int batchFailureLimit(BigDecimal minimumPassRate)
	{
		requireThat(minimumPassRate, "minimumPassRate").isNotNull();
		return BigDecimal.ONE.subtract(minimumPassRate).multiply(BigDecimal.valueOf(20)).intValue();
	}

	/**
	 * Returns the observed pass rate that a ready cell's development outcomes must reach, for the minimum pass rate
	 * {@code p_min}: {@code 1 - (1 - p_min) / 5}, which allows one fifth of the failure rate that certification
	 * tolerates.
	 *
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return the rate
	 * @throws NullPointerException if {@code minimumPassRate} is null
	 */
	public static BigDecimal minimumObservedPassRate(BigDecimal minimumPassRate)
	{
		requireThat(minimumPassRate, "minimumPassRate").isNotNull();
		return BigDecimal.ONE.subtract(BigDecimal.ONE.subtract(minimumPassRate).divide(BigDecimal.valueOf(5)));
	}

	/**
	 * Indicates whether a cell's development outcomes reach {@link #minimumObservedPassRate(BigDecimal)}. Empty counts
	 * meet this rate check; the readiness gate separately requires a minimum number of outcomes.
	 *
	 * @param counts the cell's development counts
	 * @param minimumPassRate the campaign's minimum pass rate {@code p_min}
	 * @return {@code true} if the cell meets the pass-rate threshold
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static boolean meetsPassRateThreshold(CellCounts counts, BigDecimal minimumPassRate)
	{
		requireThat(counts, "counts").isNotNull();
		requireThat(minimumPassRate, "minimumPassRate").isNotNull();
		// For the minimum pass rate p_min, passes >= (1 - (1 - p_min) / 5) n is equivalent to 5 F <= (1 - p_min) n
		BigDecimal allowed = BigDecimal.ONE.subtract(minimumPassRate).multiply(BigDecimal.valueOf(counts.outcomes()));
		return BigDecimal.valueOf(5L * counts.failures()).compareTo(allowed) <= 0;
	}

	/**
	 * Indicates whether the working candidate meets the gate: every development cell is ready, no diagnosis, round or
	 * prompt author attempt is unresolved, no ended attempt is reproducing its formal failures, the working candidate's
	 * latest outcome on every regression test case is not a failure or is a {@link RegressionTestCase#settled()
	 * settled} failure, and the incumbent baseline is met.
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
		if (development.isPaused() || campaign.reproducing().isPresent())
			return false;
		for (String cell : CellTier.developmentCellIds(campaign))
		{
			if (!isReady(development.count(cell), campaign.contract().minimumPassRate()))
				return false;
		}
		for (RegressionTestCase regression : campaign.lineage().regressionSet())
		{
			if (regression.working() == Verdict.FAIL && !regression.settled())
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
