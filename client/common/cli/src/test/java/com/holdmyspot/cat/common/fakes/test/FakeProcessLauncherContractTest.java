package com.holdmyspot.cat.common.fakes.test;

import com.holdmyspot.cat.common.test.ProcessLifecycleContract;

/**
 * Checks {@link FakeProcessLauncher} against the lifecycle used by the actual executable-file adapter contract.
 */
public final class FakeProcessLauncherContractTest extends ProcessLifecycleContract
{
	/**
	 * Creates the fake-launcher contract tests.
	 */
	public FakeProcessLauncherContractTest()
	{
	}

	@Override
	protected Fixture start()
	{
		Process process = new FakeProcessLauncher().start(new ProcessBuilder("fixture-program"));
		return new Fixture()
		{
			@Override
			public Process process()
			{
				return process;
			}

			@Override
			public void close()
			{
				process.destroyForcibly();
			}
		};
	}
}
