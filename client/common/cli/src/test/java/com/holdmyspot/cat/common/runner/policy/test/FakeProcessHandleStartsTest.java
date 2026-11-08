package com.holdmyspot.cat.common.runner.policy.test;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.test.FakeProcessHandle;

/**
 * Runs the {@link HarnessProcesses} contract against {@link FakeProcessHandle#processes(FakeProcessHandle...)}, so that
 * the tests that hold their processes in fake process handles see the starts that the operating system would report.
 */
public final class FakeProcessHandleStartsTest extends HarnessProcessesContract
{
	/**
	 * Creates the test.
	 */
	public FakeProcessHandleStartsTest()
	{
	}

	@Override
	protected Fixture create()
	{
		FakeProcessHandle running = new FakeProcessHandle(2, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t4000", true);
		FakeProcessHandle withoutStart = new FakeProcessHandle(3, null, true);
		FakeProcessHandle ended = new FakeProcessHandle(4, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t4100", false);
		return new FakeFixture(FakeProcessHandle.processes(running, withoutStart, ended), running.pid(),
			withoutStart.pid(), ended.pid());
	}

	/**
	 * Fake processes: one running with a start, one running without, and one that ended.
	 *
	 * @param processes           the lookup
	 * @param running             the running process with a start
	 * @param runningWithoutStart the running process without a start
	 * @param notRunning          the process that ended
	 */
	private record FakeFixture(HarnessProcesses processes, long running, long runningWithoutStart, long notRunning)
		implements Fixture
	{
		@Override
		public void close()
		{
		}
	}
}
