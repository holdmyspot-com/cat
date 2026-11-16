package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.test.ProcessEofContract;

/**
 * Checks the generic process lifecycle of {@link FakeHarnessCli} against the actual executable adapter's fixture
 * child. Protocol responses and caller-controlled output events are covered separately.
 */
public final class FakeHarnessCliContractTest extends ProcessEofContract
{
	/**
	 * Creates the harness-child double's lifecycle checks.
	 */
	public FakeHarnessCliContractTest()
	{
	}

	@Override
	protected EofFixture start(EofScenario scenario)
	{
		FakeHarnessCli process = new FakeHarnessCli(_ -> {});
		switch (scenario)
		{
			case INPUT_EOF_KEEP_ALIVE -> process.keepRunningWhenInputCloses();
			case OUTPUT_EOF_BEFORE_EXIT -> process.closeOutput();
			case INPUT_EOF_EXIT ->
			{
			}
		}
		return new EofFixture()
		{
			@Override
			public Process process()
			{
				return process;
			}

			@Override
			public void complete()
			{
				process.exit();
			}

			@Override
			public void close()
			{
				process.destroyForcibly();
			}
		};
	}
}
