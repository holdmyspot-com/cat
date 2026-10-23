package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.MainJvmScope;
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
	 * @throws NullPointerException if {@code environment} is null
	 */
	public MainHarnessRunnerScope(Map<String, String> environment)
	{
		super(new MainJvmScope(environment));
	}


	@Override
	protected HeartbeatTicker heartbeatTicker()
	{
		ensureOpen();
		return HeartbeatTicker.everySecond();
	}
}
