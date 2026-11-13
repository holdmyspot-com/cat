package com.holdmyspot.cat.common.optimizer.port;

import java.io.IOException;
import java.util.Map;

/** Decides whether a session may end after a completed turn. */
@FunctionalInterface
public interface TurnObserver
{
	/**
	 * Decides whether a session may end after a completed turn.
	 *
	 * @param completedTurns number of completed turns
	 * @param result evidence collected through the completed turn
	 * @return {@code true} when the session should end before its next turn, otherwise {@code false}
	 * @throws IOException if the evidence cannot be evaluated
	 */
	boolean endAfter(int completedTurns, SessionResult.Completed result) throws IOException;

	/**
	 * Returns the first completed turn at which each assertion became settled.
	 *
	 * @return each settled assertion's first completed turn, or an empty map when none are settled
	 */
	default Map<String, Integer> settledAssertions()
	{
		return Map.of();
	}
}
