package com.holdmyspot.cat.common.build.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.function.LongConsumer;
import org.testng.annotations.Test;

/**
 * Checks that each process-stop adapter affects only the requested process and tolerates an ended process.
 */
public abstract class BuildProcessStopsContract
{
	/**
	 * Starts two independent actors for the process-stop contract.
	 *
	 * @return owned actors and their stop adapter
	 * @throws IOException if fixture processes cannot start
	 * @throws InterruptedException if readiness observation is interrupted
	 */
	protected abstract Fixture startFixture() throws IOException, InterruptedException;

	/**
	 * Stops only the selected actor while its unrelated peer remains alive.
	 * <p>
	 * With the system adapter, the case starts two real fixture JVMs and waits for their readiness files, which
	 * takes seconds. The test keeps them because the OS process-handle stop is the boundary it checks.
	 *
	 * @throws IOException if fixture cleanup fails
	 * @throws InterruptedException if fixture setup or observation is interrupted
	 */
	@Test
	public void stopsSelectedActorOnly() throws IOException, InterruptedException
	{
		try (Fixture fixture = startFixture())
		{
			requireThat(fixture.selectedAlive(), "selectedActorReady").isTrue();
			requireThat(fixture.peerAlive(), "peerActorReady").isTrue();
			fixture.forceStop().accept(fixture.selectedPid());
			requireThat(fixture.awaitSelectedExit(), "selectedActorExited").isTrue();
			requireThat(fixture.selectedAlive(), "selectedActorStopped").isFalse();
			requireThat(fixture.peerAlive(), "unrequestedPeerRemainsAlive").isTrue();
		}
	}

	/**
	 * Ignores a stop request for an ended actor without stopping its live peer.
	 * <p>
	 * With the system adapter, the case starts two real fixture JVMs and waits for their readiness files, which
	 * takes seconds. The test keeps them because the OS process-handle stop is the boundary it checks.
	 *
	 * @throws IOException if fixture cleanup fails
	 * @throws InterruptedException if fixture setup or observation is interrupted
	 */
	@Test
	public void stoppingEndedActorLeavesPeerAlive() throws IOException, InterruptedException
	{
		try (Fixture fixture = startFixture())
		{
			fixture.finishSelected();
			requireThat(fixture.selectedAlive(), "selectedActorFinished").isFalse();
			fixture.forceStop().accept(fixture.selectedPid());
			requireThat(fixture.peerAlive(), "endedActorRequestPreservesPeer").isTrue();
		}
	}

	/**
	 * Describes actor state and cleanup for one process-stop adapter test.
	 */
	protected interface Fixture extends AutoCloseable
	{
		/**
		 * Returns the adapter exercised by this fixture.
		 *
		 * @return single-process forced-stop request
		 */
		LongConsumer forceStop();

		/**
		 * Returns the selected actor's process identifier.
		 *
		 * @return selected process identifier
		 */
		long selectedPid();

		/**
		 * Returns the unrelated peer's process identifier.
		 *
		 * @return peer process identifier
		 */
		long peerPid();

		/**
		 * Reports whether the selected actor is still live.
		 *
		 * @return true while selected actor is live
		 */
		boolean selectedAlive();

		/**
		 * Reports whether the unrelated peer is still live.
		 *
		 * @return true while peer is live
		 */
		boolean peerAlive();

		/**
		 * Waits for an observed selected-actor exit with a bounded safety limit.
		 *
		 * @return true if selected actor exited before the limit
		 * @throws InterruptedException if observation is interrupted
		 */
		boolean awaitSelectedExit() throws InterruptedException;

		/**
		 * Releases and joins the selected actor normally.
		 *
		 * @throws IOException if the release cannot be written
		 * @throws InterruptedException if the process join is interrupted
		 */
		void finishSelected() throws IOException, InterruptedException;

		/**
		 * Stops remaining owned actors and releases fixture resources.
		 *
		 * @throws IOException if owned processes or resources cannot be cleaned up
		 */
		@Override
		void close() throws IOException;
	}
}
