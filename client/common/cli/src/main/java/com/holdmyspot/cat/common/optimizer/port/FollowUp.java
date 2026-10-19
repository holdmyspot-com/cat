package com.holdmyspot.cat.common.optimizer.port;

import java.util.Optional;

/**
 * Chooses one more turn for a session from its replies to the turns that its request lists.
 */
@FunctionalInterface
public interface FollowUp
{
	/**
	 * Returns the turn to send after the session's last listed turn.
	 *
	 * @param result the session's result after its last listed turn
	 * @return the turn, or empty to end the session
	 * @throws NullPointerException if {@code result} is null
	 */
	Optional<String> next(SessionResult.Completed result);
}
