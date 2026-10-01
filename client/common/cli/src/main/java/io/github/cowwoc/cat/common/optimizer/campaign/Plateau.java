package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Decides when development has reached a plateau, from {@code design.md} section 2.7.
 * <p>
 * Only a complete round without a promotion establishes a plateau. A promotion ends its round, interruptions and
 * infrastructure failures never complete an author attempt, and elapsed time or tokens are never consulted.
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
	 * @return {@code true} if every category of the round received all of its author attempts
	 * @throws NullPointerException if {@code round} is null
	 */
	public static boolean isReached(Round round)
	{
		requireThat(round, "round").isNotNull();
		for (int attempts : round.attempts().values())
		{
			if (attempts < Round.ATTEMPTS)
				return false;
		}
		return true;
	}
}
