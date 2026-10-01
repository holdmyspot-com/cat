package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;

import java.util.Optional;

/**
 * The concurrency limits of {@code design.md} section 3.3. Each is a deterministic function of persisted counts and
 * the throttle state.
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
	 * Returns the risk ceiling: {@code floor(1 / (2·û))}, at least 1, where {@code û} is the one-sided 95%
	 * Clopper-Pearson upper bound on the failure rate.
	 *
	 * @param outcomes the active candidate's adopted outcomes {@code n}
	 * @param failures the active candidate's adopted failures {@code f}
	 * @return the ceiling
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
	 * Returns the active candidate's concurrency window: the smallest of the risk ceiling, the slow-start window,
	 * the campaign ceiling and the throttle's cap.
	 *
	 * @param outcomes the active candidate's adopted outcomes {@code n}
	 * @param failures the active candidate's adopted failures {@code f}
	 * @param slowStart the slow-start state
	 * @param ceiling the campaign ceiling
	 * @param throttle the throttle state
	 * @return the window
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
	 * Returns the active candidate's concurrency window in a campaign. In the formal phase, the counts include the
	 * same snapshot's development outcomes.
	 *
	 * @param campaign the campaign
	 * @return the window
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
			for (CategoryCounts counts : attempt.counts().values())
			{
				outcomes += counts.outcomes();
				failures += counts.failures();
			}
			countDevelopment = attempt.candidate().snapshot().equals(development.working().snapshot());
		}
		if (countDevelopment)
		{
			for (CategoryCounts counts : development.counts().values())
			{
				outcomes += counts.outcomes();
				failures += counts.failures();
			}
		}
		return window(outcomes, failures, campaign.slowStart(), campaign.ceiling(), campaign.throttle());
	}

	/**
	 * Returns the most trials of a formal category that may be unadopted at once: the passes still required to
	 * certify it if all of them pass, at least 1, and never more than its remaining cap.
	 *
	 * @param boundaries the attempt's boundaries
	 * @param counts the category's adopted formal counts
	 * @return the limit, 0 if the category reached its cap
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the counts exceed the cap
	 */
	public static int categoryLimit(DecisionBoundaries boundaries, CategoryCounts counts)
	{
		requireThat(boundaries, "boundaries").isNotNull();
		requireThat(counts, "counts").isNotNull();
		int remaining = boundaries.cap() - counts.outcomes();
		int required = boundaries.minimumCertifyingPasses().get(counts.failures()) - counts.passes();
		return Math.min(Math.max(1, required), remaining);
	}
}
