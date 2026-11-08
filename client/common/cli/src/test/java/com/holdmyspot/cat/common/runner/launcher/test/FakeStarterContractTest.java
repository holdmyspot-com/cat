package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionStarterContract;
import java.util.List;

/**
 * Runs the {@link RequestedSessionStarter} contract against {@link FakeStarter}, so that the request server tests that
 * start their children's runners through it see what the request servers' starters do.
 */
public final class FakeStarterContractTest extends RequestedSessionStarterContract
{
	/**
	 * Creates the test.
	 */
	public FakeStarterContractTest()
	{
	}

	@Override
	protected String harness()
	{
		return FakeStarter.HARNESS;
	}

	@Override
	protected Starts starts()
	{
		FakeStarter starter = new FakeStarter();
		return new Starts()
		{
			@Override
			public RequestedSessionStarter starter()
			{
				return starter;
			}

			@Override
			public List<StartedRunner> started()
			{
				return starter.runners().stream().map(runner -> new StartedRunner(runner.harness(), runner.arguments(),
					runner.directory(), runner.directory().resolve(RequestedSessionStarter.ERROR_FILE))).toList();
			}

			@Override
			public void close()
			{
			}
		};
	}
}
