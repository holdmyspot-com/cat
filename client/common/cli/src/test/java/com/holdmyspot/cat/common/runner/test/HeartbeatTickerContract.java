package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HeartbeatTicker;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Checks that a {@link HeartbeatTicker} runs a started action once each period until the action's handle closes, and
 * runs each started action apart from the others: closing one action's handle stops only that start, even when another
 * start uses the same action.
 */
public final class HeartbeatTickerContract
{
	/**
	 * How long a check waits for one run of an action before it fails, in seconds; far longer than any period under
	 * contract.
	 */
	private static final long PATIENCE_SECONDS = 5;

	/**
	 * The ticker under contract.
	 */
	private final Subject subject;

	/**
	 * Creates checks of one ticker.
	 *
	 * @param subject the ticker under contract
	 */
	public HeartbeatTickerContract(Subject subject)
	{
		this.subject = subject;
	}

	/**
	 * Verifies that a started action runs once in each of several periods.
	 *
	 * @throws IOException          if the action's handle cannot close
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	public void runsActionEachPeriod() throws IOException, InterruptedException
	{
		HeartbeatTicker ticker = subject.ticker();
		Semaphore runs = new Semaphore(0);
		try (Closeable _ = ticker.start(runs::release))
		{
			for (int period = 1; period <= 3; ++period)
				awaitPeriod("period " + period, runs);
		}
	}

	/**
	 * Verifies that closing the handle of one of two started actions stops that action and leaves the other running.
	 *
	 * @throws IOException          if a handle cannot close
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	public void closingStopsOnlyItsAction() throws IOException, InterruptedException
	{
		HeartbeatTicker ticker = subject.ticker();
		Semaphore closedRuns = new Semaphore(0);
		Semaphore openRuns = new Semaphore(0);
		try (Closeable _ = ticker.start(openRuns::release))
		{
			try (Closeable _ = ticker.start(closedRuns::release))
			{
				awaitPeriod("period before close", closedRuns, openRuns);
			}

			// A run of the closed action that had begun when its handle closed ends within these periods
			awaitPeriod("first period after close", openRuns);
			awaitPeriod("second period after close", openRuns);
			int runsAfterClose = closedRuns.availablePermits();
			awaitPeriod("third period after close", openRuns);
			awaitPeriod("fourth period after close", openRuns);
			requireThat(closedRuns.availablePermits(), "closedRuns").isEqualTo(runsAfterClose);
		}
	}

	/**
	 * Verifies that starting the same action twice gives each start an independent handle.
	 *
	 * @throws IOException          if a handle cannot close
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	public void sameActionStartsHaveSeparateHandles() throws IOException, InterruptedException
	{
		HeartbeatTicker ticker = subject.ticker();
		Semaphore actionRuns = new Semaphore(0);
		Semaphore periodSignals = new Semaphore(0);
		Runnable action = actionRuns::release;
		try (Closeable _ = ticker.start(periodSignals::release))
		{
			try (Closeable _ = ticker.start(action))
			{
				try (Closeable _ = ticker.start(action))
				{
					awaitPeriod("period with both starts", actionRuns, actionRuns, periodSignals);
				}
				awaitPeriod("first period after one handle closes", actionRuns, periodSignals);
				awaitPeriod("second period after one handle closes", actionRuns, periodSignals);
			}
			awaitPeriod("settling after the final handle closes", periodSignals);
			awaitPeriod("first period after the final handle closes", periodSignals);
			int runsAfterClose = actionRuns.availablePermits();
			awaitPeriod("second period after the final handle closes", periodSignals);
			awaitPeriod("third period after the final handle closes", periodSignals);
			requireThat(actionRuns.availablePermits(), "actionRuns").isEqualTo(runsAfterClose);
		}
	}

	/**
	 * Lets one period pass and waits for each of some actions to run in it.
	 *
	 * @param period what the period is, for the failure message
	 * @param runs   the runs of each action
	 * @throws InterruptedException if the thread is interrupted while it waits
	 * @throws AssertionError       if an action does not run in time
	 */
	private void awaitPeriod(String period, Semaphore... runs) throws InterruptedException
	{
		subject.passPeriod();
		for (Semaphore run : runs)
		{
			if (!run.tryAcquire(PATIENCE_SECONDS, TimeUnit.SECONDS))
				throw new AssertionError("An action did not run in the " + period + " within " + PATIENCE_SECONDS + " s");
		}
	}

	/**
	 * The ticker under contract.
	 */
	public interface Subject
	{
		/**
		 * Returns a ticker that runs no action yet.
		 *
		 * @return the ticker
		 */
		HeartbeatTicker ticker();

		/**
		 * Lets one period of the ticker pass, or returns at once if the ticker's periods pass on their own.
		 */
		void passPeriod();
	}
}
