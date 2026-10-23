package com.holdmyspot.cat.common.runner;

import com.holdmyspot.cat.common.AbstractJvmScope;
import com.holdmyspot.cat.common.JvmScope;

/**
 * Owns the mapper and services shared while one runner process serves requests for a harness execution.
 * <p>
 * {@link MainHarnessRunnerScope} supplies the production clock and heartbeat; tests supply their own scope.
 */
public abstract class AbstractHarnessRunnerScope extends AbstractJvmScope implements HarnessRunnerScope
{
	/**
	 * Creates an open harness-run scope.
	 *
	 * @param parent the owned process scope
	 * @throws NullPointerException if {@code parent} is null
	 */
	protected AbstractHarnessRunnerScope(JvmScope parent)
	{
		super(parent, true);
	}

	/**
	 * Returns the heartbeat that runs the bridge's check for a quiet turn.
	 *
	 * @return the heartbeat
	 *
	 * @throws IllegalStateException if this scope is closed
	 */
	protected abstract HeartbeatTicker heartbeatTicker();
}
