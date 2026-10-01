package io.github.cowwoc.cat.codex.optimizer;

import java.io.IOException;

/**
 * Something that happened in a session's runner, which the session driver waits for.
 * <p>
 * <b>Thread Safety:</b> Implementations are immutable and thread-safe.
 */
sealed interface Message
{
	/**
	 * The runner started its harness and is ready for turns.
	 *
	 * @param runId the identifier of the run directory
	 */
	record Started(String runId) implements Message
	{
	}

	/**
	 * The runner reports that a turn ended with a terminal result.
	 *
	 * @param response the final assistant response of the turn
	 */
	record TurnCompleted(String response) implements Message
	{
	}

	/**
	 * The runner returned or failed.
	 *
	 * @param failure why the runner failed, or {@code null} if it returned normally
	 */
	record Ended(IOException failure) implements Message
	{
	}

	/**
	 * Wakes the driver so that it re-examines the session's state, such as after an interrupt request.
	 */
	record Wake() implements Message
	{
	}
}
