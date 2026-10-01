package io.github.cowwoc.cat.common.runner;

import java.time.Clock;

/**
 * The production {@link HarnessRunnerScope}: the system clock and a heartbeat that runs once a second on a
 * background thread.
 */
public final class MainHarnessRunnerScope extends HarnessRunnerScope
{
	/**
	 * Creates an open harness-run scope.
	 */
	public MainHarnessRunnerScope()
	{
	}

	@Override
	protected Clock clock()
	{
		ensureOpen();
		return Clock.systemUTC();
	}

	@Override
	protected HeartbeatTicker heartbeatTicker()
	{
		ensureOpen();
		return HeartbeatTicker.everySecond();
	}
}
