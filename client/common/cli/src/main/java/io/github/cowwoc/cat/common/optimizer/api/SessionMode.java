package io.github.cowwoc.cat.common.optimizer.api;

/**
 * Defines how many ordered turns share one fresh native session.
 */
public enum SessionMode
{
	/**
	 * One turn in one fresh native session.
	 */
	SINGLE_TURN,
	/**
	 * Multiple turns in one fresh native session.
	 */
	MULTI_TURN
}
