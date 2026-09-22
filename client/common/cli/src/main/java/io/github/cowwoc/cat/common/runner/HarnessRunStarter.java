package io.github.cowwoc.cat.common.runner;

import java.io.IOException;


/**
 * Starts an isolated harness execution for the line-delimited JSON request bridge.
 */
@FunctionalInterface
public interface HarnessRunStarter
{
	/**
	 * Starts one live isolated run.
	 *
	 * @return the live run session
	 *
	 * @throws IOException if the run cannot be started
	 */
	HarnessRunSession start() throws IOException;
}
