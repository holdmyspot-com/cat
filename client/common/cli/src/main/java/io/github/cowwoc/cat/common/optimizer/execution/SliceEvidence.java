package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.campaign.AssumptionCheck;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What one slice observed that the campaign does not persist: trial durations, the concurrency at which each outcome
 * was observed, and token usage per session role.
 */
final class SliceEvidence
{
	/**
	 * The number of completed trials after which the admission cutoff uses observed durations instead of time limits.
	 */
	private static final int DURATION_SAMPLES = 5;
	/**
	 * The percentile of observed durations that the admission cutoff expects a trial to take.
	 */
	private static final double DURATION_PERCENTILE = 0.9;
	private final List<Duration> durations = new ArrayList<>();
	private final List<AssumptionCheck.Observation> observations = new ArrayList<>();
	private final Map<SessionRole, List<TokenUsage>> usage = new EnumMap<>(SessionRole.class);

	/**
	 * Creates empty evidence.
	 */
	SliceEvidence()
	{
	}

	/**
	 * Records how long a completed trial session took.
	 *
	 * @param duration the duration
	 */
	void recordDuration(Duration duration)
	{
		durations.add(duration);
	}

	/**
	 * Returns how long the admission cutoff expects a trial to take: the 90th-percentile duration of the completed
	 * trials once 5 have completed, otherwise the test definition's time limit.
	 *
	 * @param timeLimit the trial's time limit
	 * @return the expected duration
	 */
	Duration expectedDuration(Duration timeLimit)
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
	 * Records a session's token usage.
	 *
	 * @param role the session's role
	 * @param tokens the usage, or {@code null} if the harness did not report it
	 */
	void addUsage(SessionRole role, TokenUsage tokens)
	{
		if (tokens != null)
			usage.computeIfAbsent(role, _ -> new ArrayList<>()).add(tokens);
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
		Map<SessionRole, List<TokenUsage>> tokens = new EnumMap<>(SessionRole.class);
		for (Map.Entry<SessionRole, List<TokenUsage>> entry : usage.entrySet())
			tokens.put(entry.getKey(), List.copyOf(entry.getValue()));
		return new ReportEvidence(observations, tokens, List.<Path>of(), List.<Path>of(), symbolicLinks, interrupted,
			infrastructureFailure);
	}
}
