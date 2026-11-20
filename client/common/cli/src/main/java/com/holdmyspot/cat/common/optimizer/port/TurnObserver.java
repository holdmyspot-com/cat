package com.holdmyspot.cat.common.optimizer.port;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

	/**
	 * Returns the observer's latest completed-turn state.
	 *
	 * @return progress through the latest completed turn
	 */
	default Progress progress()
	{
		return new Progress(0, Map.of(), false);
	}

	/**
	 * The observer's durable state through one completed turn.
	 *
	 * @param completedTurn the latest completed turn
	 * @param settledAssertions each assertion's first settlement turn
	 * @param endedEarly whether the latest turn should end the trial before its next turn
	 */
	record Progress(int completedTurn, Map<String, Integer> settledAssertions, boolean endedEarly)
	{
		/**
		 * Creates completed-turn progress.
		 *
		 * @param completedTurn the latest completed turn
		 * @param settledAssertions each assertion's first settlement turn
		 * @param endedEarly whether the latest turn should end the trial before its next turn
		 * @throws NullPointerException if the map or an entry is null
		 * @throws IllegalArgumentException if a settlement turn is outside the completed prefix
		 */
		public Progress
		{
			if (completedTurn < 0)
				throw new IllegalArgumentException("completedTurn must not be negative");
			Objects.requireNonNull(settledAssertions, "settledAssertions");
			for (Map.Entry<String, Integer> entry : settledAssertions.entrySet())
			{
				Objects.requireNonNull(entry.getKey(), "assertionId");
				Objects.requireNonNull(entry.getValue(), "settledTurn");
				if (entry.getValue() < 1 || entry.getValue() > completedTurn)
					throw new IllegalArgumentException("A settlement turn must be between 1 and completedTurn");
			}
			settledAssertions = Collections.unmodifiableMap(new LinkedHashMap<>(settledAssertions));
		}
	}
}
