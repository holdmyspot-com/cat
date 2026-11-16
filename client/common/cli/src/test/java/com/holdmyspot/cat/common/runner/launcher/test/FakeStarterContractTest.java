package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.contract.test.RequestedSessionStarterContract;
import java.util.List;

/**
 * Checks that {@link FakeStarter} starts supported requests with the supplied arguments and directory and refuses
 * unsupported harnesses.
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
				return starter.started();
			}

			@Override
			public void close()
			{
			}
		};
	}
}
