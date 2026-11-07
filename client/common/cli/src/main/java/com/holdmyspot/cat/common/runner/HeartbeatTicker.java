package com.holdmyspot.cat.common.runner;

import java.io.Closeable;

/**
 * Runs an action at a steady pace, so the bridge can tell its caller that a quiet turn is still running.
 * <p>
 * A {@link HarnessRunnerScope} supplies the bridge's ticker. {@link MainHarnessRunnerScope} supplies one that runs each
 * action once a second on a background thread; a test's scope supplies a ticker whose actions the test runs itself.
 */
@FunctionalInterface
public interface HeartbeatTicker
{
	/**
	 * Starts running an action at a steady pace, apart from any other action that the ticker runs.
	 *
	 * @param tick the action; it must not throw
	 * @return stops running the action when closed
	 */
	Closeable start(Runnable tick);
}
