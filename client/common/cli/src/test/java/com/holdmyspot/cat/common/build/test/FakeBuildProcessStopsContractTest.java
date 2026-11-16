package com.holdmyspot.cat.common.build.test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies the process-stop contract to the same controlled dispatcher used by worker lifecycle tests.
 */
public final class FakeBuildProcessStopsContractTest extends BuildProcessStopsContract
{
	/**
	 * Creates two independent in-memory actors for the shared adapter contract.
	 *
	 * @return controlled actor fixture
	 */
	@Override
	protected Fixture startFixture()
	{
		return new FakeFixture();
	}

	private static final class FakeFixture implements Fixture
	{
		private static final long SELECTED_PID = 61_001;
		private static final long PEER_PID = 61_002;
		private final CountDownLatch selectedExited = new CountDownLatch(1);
		private final CountDownLatch peerExited = new CountDownLatch(1);
		private final AtomicBoolean selectedAlive = new AtomicBoolean(true);
		private final AtomicBoolean peerAlive = new AtomicBoolean(true);
		private final FakeBuildProcessStops forceStop = new FakeBuildProcessStops(Map.of(
			SELECTED_PID, () ->
			{
				selectedAlive.set(false);
				selectedExited.countDown();
			}, PEER_PID, () ->
			{
				peerAlive.set(false);
				peerExited.countDown();
			}));

		@Override
		public FakeBuildProcessStops forceStop()
		{
			return forceStop;
		}

		@Override
		public long selectedPid()
		{
			return SELECTED_PID;
		}

		@Override
		public long peerPid()
		{
			return PEER_PID;
		}

		@Override
		public boolean selectedAlive()
		{
			return selectedAlive.get();
		}

		@Override
		public boolean peerAlive()
		{
			return peerAlive.get();
		}

		@Override
		public boolean awaitSelectedExit() throws InterruptedException
		{
			return selectedExited.await(5, TimeUnit.SECONDS);
		}

		@Override
		public void finishSelected()
		{
			selectedAlive.set(false);
			selectedExited.countDown();
		}

		@Override
		public void close() throws IOException
		{
			finishSelected();
			peerAlive.set(false);
			peerExited.countDown();
		}
	}
}
