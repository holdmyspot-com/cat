package com.holdmyspot.cat.common.runner;

/**
 * Says what a turn is for, which decides where a harness ends it when its own rule would hold the turn open.
 */
public enum TurnMode
{
	/**
	 * The caller judges the turn's reply, so the turn is not over until the harness has nothing left to add to it. A
	 * Claude turn then waits for its background tasks to finish.
	 */
	STANDARD,
	/**
	 * The turn belongs to a conversation in which the asking session plays the user, so nothing scores the reply and a
	 * message written while the harness works is queued by the harness. A Claude turn then ends as soon as Claude
	 * reports that it is idle, whatever background work is still listed.
	 */
	CONVERSATION
}
