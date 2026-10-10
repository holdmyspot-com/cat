package io.github.cowwoc.cat.common.runner.fakes.test;

import io.github.cowwoc.cat.common.fakes.test.ManualClock;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

import java.time.Instant;

/**
 * The {@link HarnessRunnerScope} of a test: a clock that starts at {@code 2026-10-01T00:00:00Z} and moves only when
 * the test moves it, and a heartbeat that runs only when the test runs it.
 */
public final class TestHarnessRunnerScope extends HarnessRunnerScope
{
	private final ManualClock clock = new ManualClock(Instant.parse("2026-10-01T00:00:00Z"));
	private final ManualTicker ticker = new ManualTicker();

	/**
	 * Creates an open harness-run scope.
	 */
	public TestHarnessRunnerScope()
	{
	}

	@Override
	public ManualClock clock()
	{
		ensureOpen();
		return clock;
	}

	@Override
	public ManualTicker heartbeatTicker()
	{
		ensureOpen();
		return ticker;
	}
}
