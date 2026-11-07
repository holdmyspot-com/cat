package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.runner.HeartbeatTicker;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;

import java.time.Duration;

/**
 * Exposes the heartbeat of {@link MainHarnessRunnerScope} at a shorter period, so that its tests need not wait a
 * second for each beat.
 */
@FunctionalInterface
public interface MainHarnessRunnerScopeAccess
{
	/**
	 * Returns the scope's heartbeat ticker with another period.
	 *
	 * @param period how often the ticker runs each action
	 * @return the ticker
	 * @throws NullPointerException     if {@code period} is null
	 * @throws IllegalArgumentException if {@code period} is not positive
	 */
	HeartbeatTicker heartbeatEvery(Duration period);
}
