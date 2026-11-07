package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.HeartbeatTicker;
import com.holdmyspot.cat.common.runner.MainHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.fakes.test.ManualTicker;
import com.holdmyspot.cat.common.secrets.MainHarnessRunnerScopeSecrets;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Duration;

/**
 * Runs the {@link HeartbeatTickerContract} on the manual ticker of the bridge tests and on the heartbeat of
 * {@link MainHarnessRunnerScope}, the latter at a period of a few milliseconds instead of its second.
 */
public final class HeartbeatTickerTest
{
	/**
	 * The period of the scope's heartbeat under test.
	 */
	private static final Duration SHORT_PERIOD = Duration.ofMillis(5);

	/**
	 * Verifies that the manual ticker runs its actions as the scope's heartbeat does, a period each time the test
	 * ticks it.
	 *
	 * @throws IOException          if a handle cannot close
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void manualTickerBeats() throws IOException, InterruptedException
	{
		ManualTicker ticker = new ManualTicker();
		verify(new HeartbeatTickerContract(new HeartbeatTickerContract.Subject()
		{
			@Override
			public HeartbeatTicker ticker()
			{
				return ticker;
			}

			@Override
			public void passPeriod()
			{
				ticker.tick();
			}
		}));
	}

	/**
	 * Verifies that the scope's heartbeat runs its actions on its own at a steady pace.
	 *
	 * @throws IOException          if a handle cannot close
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void scopeHeartbeatBeats() throws IOException, InterruptedException
	{
		verify(new HeartbeatTickerContract(new HeartbeatTickerContract.Subject()
		{
			@Override
			public HeartbeatTicker ticker()
			{
				return MainHarnessRunnerScopeSecrets.getMainHarnessRunnerScopeAccess().heartbeatEvery(SHORT_PERIOD);
			}

			@Override
			public void passPeriod()
			{
				// The heartbeat's periods pass in real time
			}
		}));
	}

	/**
	 * Runs every case of a contract.
	 *
	 * @param contract the contract
	 * @throws IOException          if a handle cannot close
	 * @throws InterruptedException if the thread is interrupted
	 */
	private static void verify(HeartbeatTickerContract contract) throws IOException, InterruptedException
	{
		contract.runsActionEachPeriod();
		contract.closingStopsOnlyItsAction();
	}
}
