package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.secrets.MainHarnessRunnerScopeSecrets;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The production {@link HarnessRunnerScope}: the system clock and a heartbeat that runs once a second on a
 * background thread.
 */
public final class MainHarnessRunnerScope extends AbstractHarnessRunnerScope
{
	/**
	 * How often the heartbeat runs each action.
	 */
	private static final Duration HEARTBEAT_PERIOD = Duration.ofSeconds(1);

	static
	{
		MainHarnessRunnerScopeSecrets.setMainHarnessRunnerScopeAccess(MainHarnessRunnerScope::heartbeatEvery);
	}

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

	/**
	 * Returns a ticker that runs each action on a background thread of its own, once a period, starting one period
	 * after the action starts.
	 *
	 * @param period how often the ticker runs each action
	 * @return the ticker
	 * @throws NullPointerException     if {@code period} is null
	 * @throws IllegalArgumentException if {@code period} is not positive
	 */
	private static HeartbeatTicker heartbeatEvery(Duration period)
	{
		requireThat(period, "period").isGreaterThan(Duration.ZERO);
		long nanos = period.toNanos();
		return tick ->
		{
			ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
				Thread.ofVirtual().name("bridge-heartbeat").factory());
			scheduler.scheduleAtFixedRate(tick, nanos, nanos, TimeUnit.NANOSECONDS);
			return scheduler::shutdownNow;
		};
	}

	@Override
	protected HeartbeatTicker heartbeatTicker()
	{
		ensureOpen();
		return heartbeatEvery(HEARTBEAT_PERIOD);
	}
}
