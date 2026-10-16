package io.github.cowwoc.cat.common.optimizer.adapter;

import java.io.IOException;

/**
 * Something that happened in a session's runner, which the session driver waits for.
 * <p>
 * <b>Thread Safety:</b> Implementations are immutable and thread-safe.
 */
public sealed interface Message permits Message.Started, TurnCompleted, Message.TurnFailed, Message.Ended,
	Message.Wake
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
	 * The runner reports that a turn ended without a terminal result.
	 *
	 * @param error why the turn failed
	 */
	record TurnFailed(String error) implements Message
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
