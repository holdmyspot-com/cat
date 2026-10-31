package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.evidence.CommandSummary;

import java.time.Duration;

/**
 * How the shell commands of a group of completed trial sessions ran.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param sessions          the number of completed trial sessions
 * @param slowKnown         the number of those sessions whose longest command is known
 * @param slow              the number of those sessions whose longest command ran for {@link #SLOW} or longer
 * @param emptyRunningKnown the number of those sessions whose count of empty results from running commands is known
 * @param emptyRunning      the number of those sessions whose agents received at least one empty result from a
 *                          command that was still running
 */
public record CommandCounts(long sessions, long slowKnown, long slow, long emptyRunningKnown, long emptyRunning)
{
	/**
	 * The duration from which a command counts as slow. Codex returns a result to the model once a command has run
	 * this long, even if the command is still running.
	 */
	public static final Duration SLOW = Duration.ofSeconds(10);
	/**
	 * No session has been counted.
	 */
	public static final CommandCounts EMPTY = new CommandCounts(0, 0, 0, 0, 0);

	/**
	 * Creates counts.
	 *
	 * @param sessions          the number of completed trial sessions
	 * @param slowKnown         the number of those sessions whose longest command is known
	 * @param slow              the number of those sessions whose longest command ran for {@link #SLOW} or longer
	 * @param emptyRunningKnown the number of those sessions whose count of empty results from running commands is
	 *                          known
	 * @param emptyRunning      the number of those sessions whose agents received at least one empty result from a
	 *                          command that was still running
	 * @throws IllegalArgumentException if a count is negative, a known count exceeds {@code sessions}, or a count of
	 *                                  sessions with a property exceeds its known count
	 */
	public CommandCounts
	{
		requireThat(sessions, "sessions").isNotNegative();
		requireThat(slowKnown, "slowKnown").isBetween(0L, true, sessions, true);
		requireThat(slow, "slow").isBetween(0L, true, slowKnown, true);
		requireThat(emptyRunningKnown, "emptyRunningKnown").isBetween(0L, true, sessions, true);
		requireThat(emptyRunning, "emptyRunning").isBetween(0L, true, emptyRunningKnown, true);
	}

	/**
	 * Counts one more completed session.
	 *
	 * @param commands how the session's commands ran
	 * @return the updated counts
	 * @throws NullPointerException if {@code commands} is null
	 */
	public CommandCounts add(CommandSummary commands)
	{
		requireThat(commands, "commands").isNotNull();
		long updatedSlowKnown = slowKnown;
		long updatedSlow = slow;
		if (commands.slowest() != null)
		{
			updatedSlowKnown += 1;
			if (commands.slowest().compareTo(SLOW) >= 0)
				updatedSlow += 1;
		}
		long updatedEmptyKnown = emptyRunningKnown;
		long updatedEmpty = emptyRunning;
		if (commands.emptyRunningResults() != null)
		{
			updatedEmptyKnown += 1;
			if (commands.emptyRunningResults() > 0)
				updatedEmpty += 1;
		}
		return new CommandCounts(sessions + 1, updatedSlowKnown, updatedSlow, updatedEmptyKnown, updatedEmpty);
	}
}
