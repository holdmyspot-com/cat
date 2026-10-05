package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.util.Map;

/**
 * Detects a development plateau: a complete round without a promotion while the working candidate has more than
 * {@value EditOrContinue#FAILURE_LIMIT} representative development failures in a cell (a requirement category under one
 * runner profile).
 * <p>
 * Only a complete round without a promotion can establish a plateau. A promotion ends its round, interruptions and
 * infrastructure failures never complete an author attempt, and elapsed time or tokens are never consulted. A complete
 * round without a promotion while every cell has at most {@value EditOrContinue#FAILURE_LIMIT} failures ends, and the
 * working candidate resumes its representative trials.
 */
public final class Plateau
{
	/**
	 * Prevents instantiation.
	 */
	private Plateau()
	{
	}

	/**
	 * Indicates whether a round is complete.
	 *
	 * @param round a round that has not promoted a candidate
	 * @return {@code true} if every cell in the round received all of its author attempts
	 * @throws NullPointerException if {@code round} is null
	 */
	public static boolean isComplete(Round round)
	{
		requireThat(round, "round").isNotNull();
		for (int attempts : round.attempts().values())
		{
			if (attempts < round.maxAttempts())
				return false;
		}
		return !round.weak() || round.pendingAuthorAttempts().isEmpty() && round.validationAttempts().stream().
			allMatch(attempt -> attempt.runs().stream().allMatch(run -> run.verdict() != null));
	}

	/**
	 * Indicates whether a round establishes a plateau.
	 *
	 * @param round a round that has not promoted a candidate
	 * @param counts the working candidate's development counts per cell
	 * @return {@code true} if the round is complete and a cell has more than
	 *   {@value EditOrContinue#FAILURE_LIMIT} failures
	 * @throws NullPointerException if {@code round} or {@code counts} are null
	 */
	public static boolean isReached(Round round, Map<String, CellCounts> counts)
	{
		requireThat(counts, "counts").isNotNull();
		if (!isComplete(round))
			return false;
		for (CellCounts cell : counts.values())
		{
			if (cell.failures() > EditOrContinue.FAILURE_LIMIT)
				return true;
		}
		return false;
	}
}
