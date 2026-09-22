package io.github.cowwoc.cat.common.runner;

import java.io.IOException;


/**
 * Receives one unprocessed output event emitted while a harness processes a request.
 */
@FunctionalInterface
public interface HarnessEventListener
{
	/**
	 * Receives one non-blank native-harness event line.
	 *
	 * @param event the raw event line
	 *
	 * @throws IOException if forwarding the event fails
	 */
	void accept(String event) throws IOException;
}
