package io.github.cowwoc.cat.common.optimizer.adapter;

import java.util.List;

/**
 * The runner reports that a turn ended with a terminal result.
 *
 * @param assistantMessages every assistant text message of the turn, in order
 */
record TurnCompleted(List<String> assistantMessages) implements Message
{
	/**
	 * Copies the messages so queued turn evidence cannot change.
	 *
	 * @param assistantMessages the ordered assistant text messages
	 * @throws NullPointerException if {@code assistantMessages} is null or contains null
	 */
	TurnCompleted
	{
		assistantMessages = List.copyOf(assistantMessages);
	}
}
