package io.github.cowwoc.cat.common.runner;

import java.io.Closeable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs an action at a steady pace, so the bridge can tell its caller that a quiet turn is still running.
 * <p>
 * Unit tests supply their own ticker and run the action themselves.
 */
@FunctionalInterface
interface HeartbeatTicker
{
	/**
	 * Returns a ticker that runs its action once a second on a background thread.
	 * <p>
	 * Only the bridge's public entry points create this ticker. Unit tests supply their own.
	 *
	 * @return the ticker
	 */
	static HeartbeatTicker everySecond()
	{
		return tick ->
		{
			ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
				Thread.ofVirtual().name("bridge-heartbeat").factory());
			scheduler.scheduleAtFixedRate(tick, 1, 1, TimeUnit.SECONDS);
			return scheduler::shutdownNow;
		};
	}

	/**
	 * Starts running an action at a steady pace.
	 *
	 * @param tick the action; it must not throw
	 * @return stops running the action when closed
	 */
	Closeable start(Runnable tick);
}
