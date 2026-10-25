package com.holdmyspot.cat.common.optimizer.execution;

import com.holdmyspot.cat.common.optimizer.campaign.AssumptionCheck;
import com.holdmyspot.cat.common.optimizer.report.ReportEvidence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Observations from one driver run that the campaign does not persist: trial durations, the concurrency at which each
 * outcome was observed.
 */
public final class SliceEvidence
{
	/**
	 * Use observed durations to decide whether to admit another trial once this many trials have completed.
	 */
	private static final int DURATION_SAMPLES = 5;
	/**
	 * Use this percentile of completed trial durations to decide whether another trial can finish within the budget.
	 */
	private static final double DURATION_PERCENTILE = 0.9;
	private final List<Duration> durations = new ArrayList<>();
	private final List<AssumptionCheck.Observation> observations = new ArrayList<>();

	/**
	 * Creates empty evidence.
	 */
	public SliceEvidence()
	{
	}

	/**
	 * Records how long a completed trial session took.
	 *
	 * @param duration the duration
	 */
	public void recordDuration(Duration duration)
	{
		durations.add(duration);
	}

	/**
	 * Returns how long the admission cutoff expects a trial to take: the 90th-percentile duration of the completed
	 * trials once 5 have completed, otherwise the prompt test's time limit.
	 *
	 * @param timeLimit the trial's time limit
	 * @return the expected duration
	 */
	public Duration expectedDuration(Duration timeLimit)
	{
		if (durations.size() < DURATION_SAMPLES)
			return timeLimit;
		List<Duration> sorted = new ArrayList<>(durations);
		sorted.sort(null);
		int rank = (int) Math.ceil(DURATION_PERCENTILE * sorted.size());
		return sorted.get(rank - 1);
	}

	/**
	 * Records an outcome and the number of trial sessions that ran when its session started.
	 *
	 * @param observation the observation
	 */
	void observe(AssumptionCheck.Observation observation)
	{
		observations.add(observation);
	}

	/**
	 * Returns the evidence for the slice's status report.
	 *
	 * @param symbolicLinks {@code true} if staging replaced symbolic links with copies
	 * @param interrupted {@code true} if the host interrupted the slice
	 * @param infrastructureFailure why the slice could not continue, or {@code null} if it could
	 * @return the evidence
	 */
	ReportEvidence toReportEvidence(boolean symbolicLinks, boolean interrupted, String infrastructureFailure)
	{
		return new ReportEvidence(observations, symbolicLinks, interrupted, infrastructureFailure);
	}
}
