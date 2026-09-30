package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * Defines whether a confirmed test uses one or several turns.
 */
public enum SessionMode
{
	/**
 * One fresh turn.
 */
	SINGLE_TURN,
	/**
 * Several turns in one fresh session.
 */
	MULTI_TURN
}
