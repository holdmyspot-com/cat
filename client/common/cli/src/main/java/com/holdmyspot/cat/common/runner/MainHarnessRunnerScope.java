package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Waiting;
import java.util.Map;

/**
 * The production {@link HarnessRunnerScope}: the system clock and a heartbeat that runs once a second on a
 * background thread.
 */
public final class MainHarnessRunnerScope extends AbstractHarnessRunnerScope
{
	/**
	 * Creates an open harness-run scope.
	 *
	 * @param environment the process environment
	 * @param waiting how the process waits between the attempts of a retried operation
	 * @throws NullPointerException if any argument is null
	 */
	public MainHarnessRunnerScope(Map<String, String> environment, Waiting waiting)
	{
		super(new MainJvmScope(environment, waiting));
	}


	@Override
	protected HeartbeatTicker heartbeatTicker()
	{
		ensureOpen();
		return HeartbeatTicker.everySecond();
	}
}
