package com.holdmyspot.cat.common.runner.fakes.test;

import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.AbstractHarnessRunnerScope;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import java.time.Instant;

/**
 * The {@link HarnessRunnerScope} of a test: a clock that starts at {@code 2026-10-01T00:00:00Z} and moves only when
 * the test moves it, and a heartbeat that runs only when the test runs it.
 */
public final class TestHarnessRunnerScope extends AbstractHarnessRunnerScope
{
	private final ManualClock clock = new ManualClock(Instant.parse("2026-10-01T00:00:00Z"));
	private final ManualTicker ticker = new ManualTicker();

	/**
	 * Creates an open harness-run scope.
	 */
	public TestHarnessRunnerScope()
	{
		super(new TestJvmScope());
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
