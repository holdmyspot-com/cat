package com.holdmyspot.cat.common.optimizer.evidence;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;

/**
 * How long a session's shell commands ran and how often an agent received a result from a command that had printed
 * nothing yet while it was still running, as the harness's records show them. A value that the harness's records do
 * not show is unknown rather than 0.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param slowest             the duration of the session's longest shell command, {@link Duration#ZERO} if the
 *                            session ran none, or {@code null} if the records do not show how long one of its
 *                            commands ran
 * @param emptyRunningResults the number of results that an agent received from a shell command that was still
 *                            running and that held none of its output, or {@code null} if the records do not show
 *                            whether an agent received such a result
 */
public record CommandSummary(Duration slowest, Integer emptyRunningResults)
{
	/**
	 * The summary of a session whose records show neither value.
	 */
	public static final CommandSummary UNKNOWN = new CommandSummary(null, null);

	/**
	 * Creates a summary.
	 *
	 * @param slowest             the duration of the session's longest shell command, {@link Duration#ZERO} if the
	 *                            session ran none, or {@code null} if it is unknown
	 * @param emptyRunningResults the number of results that an agent received from a shell command that was still
	 *                            running and that held none of its output, or {@code null} if it is unknown
	 * @throws IllegalArgumentException if {@code slowest} or {@code emptyRunningResults} are negative
	 */
	public CommandSummary
	{
		if (slowest != null)
			requireThat(slowest, "slowest").isGreaterThanOrEqualTo(Duration.ZERO);
		if (emptyRunningResults != null)
			requireThat(emptyRunningResults, "emptyRunningResults").isNotNegative();
	}
}
