package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;

import java.util.Optional;

/**
 * Limits simultaneous trials from recorded outcomes, growth after passing trials and throttling after rate limits.
 */
public final class ConcurrencyPolicy
{
	/**
	 * Prevents instantiation.
	 */
	private ConcurrencyPolicy()
	{
	}

	/**
	 * Returns the maximum concurrent trials allowed by the observed failure rate: {@code floor(1 / (2·û))}, at least
	 * 1, where {@code û} is the one-sided 95% Clopper-Pearson upper bound on the failure rate.
	 *
	 * @param outcomes the active candidate's adopted outcomes {@code n}
	 * @param failures the active candidate's adopted failures {@code f}
	 * @return the maximum number of simultaneous trials allowed by the failure-rate bound
	 * @throws IllegalArgumentException if {@code outcomes} is negative or {@code failures} is outside [0, outcomes]
	 */
	public static int riskCeiling(int outcomes, int failures)
	{
		requireThat(outcomes, "outcomes").isNotNegative();
		requireThat(failures, "failures").isBetween(0, true, outcomes, true);
		double failureRate = ExploratoryBounds.clopperPearsonUpper(failures, outcomes);
		return Math.max(1, (int) Math.floor(1 / (2 * failureRate)));
	}

	/**
	 * Returns the maximum simultaneous trials for the active candidate: the smallest limit from the failure rate,
	 * growth after passing trials, the campaign configuration and throttling after rate limits.
	 *
	 * @param outcomes the active candidate's adopted outcomes {@code n}
	 * @param failures the active candidate's adopted failures {@code f}
	 * @param slowStart the concurrency limit that grows after passing trials and resets after a failure
	 * @param ceiling the configured maximum simultaneous trials for the campaign
	 * @param throttle the temporary concurrency limit after rate-limit rejections
	 * @return the maximum simultaneous trials
	 * @throws NullPointerException if {@code slowStart} or {@code throttle} are null
	 * @throws IllegalArgumentException if a count is out of range or {@code ceiling} is not positive
	 */
	public static int window(int outcomes, int failures, SlowStart slowStart, int ceiling, Throttle throttle)
	{
		requireThat(slowStart, "slowStart").isNotNull();
		requireThat(ceiling, "ceiling").isPositive();
		requireThat(throttle, "throttle").isNotNull();
		int window = Math.min(Math.min(riskCeiling(outcomes, failures), slowStart.size()), ceiling);
		return throttle.limit(window);
	}

	/**
	 * Returns the maximum simultaneous trials for the candidate being measured. During certification, include the
	 * development outcomes only when they measured the same prompt snapshot as the running attempt.
	 *
	 * @param campaign the campaign
	 * @return the maximum simultaneous trials
	 * @throws NullPointerException if {@code campaign} is null
	 */
	public static int window(Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		Development development = campaign.development();
		int outcomes = 0;
		int failures = 0;
		Optional<CertificationAttempt> running = campaign.runningAttempt();
		boolean countDevelopment = true;
		if (running.isPresent())
		{
			CertificationAttempt attempt = running.get();
			for (CellCounts counts : attempt.counts().values())
			{
				outcomes += counts.outcomes();
				failures += counts.failures();
			}
			countDevelopment = attempt.candidate().snapshot().equals(development.working().snapshot());
		}
		if (countDevelopment)
		{
			for (CellCounts counts : development.counts().values())
			{
				outcomes += counts.outcomes();
				failures += counts.failures();
			}
		}
		return window(outcomes, failures, campaign.slowStart(), campaign.ceiling(), campaign.throttle());
	}

	/**
	 * Returns the most certification trials of a cell (category and trial profile) that may await inclusion in
	 * evidence at once: the passes still required to certify it, at least 1, and never more than its remaining cap.
	 *
	 * @param boundaries the attempt's boundaries
	 * @param counts the cell's certification outcomes included in evidence
	 * @return the limit, 0 if the cell reached its cap
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the counts exceed the cap
	 */
	public static int cellLimit(DecisionBoundaries boundaries, CellCounts counts)
	{
		requireThat(boundaries, "boundaries").isNotNull();
		requireThat(counts, "counts").isNotNull();
		requireThat(counts.outcomes(), "counts.outcomes()").isLessThanOrEqualTo(boundaries.cap(), "boundaries.cap()");

		int remaining = boundaries.cap() - counts.outcomes();
		int required = boundaries.minimumCertifyingPasses().get(counts.failures()) - counts.passes();
		return Math.min(Math.max(1, required), remaining);
	}
}
