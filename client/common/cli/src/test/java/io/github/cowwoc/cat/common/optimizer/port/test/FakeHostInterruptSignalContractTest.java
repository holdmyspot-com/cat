package io.github.cowwoc.cat.common.optimizer.port.test;

import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.HostInterruptSignalContract;

import java.io.IOException;
import java.time.Instant;

import org.testng.annotations.Test;

/**
 * Checks {@link FakeHostInterruptSignal} using the same {@link HostInterruptSignalContract} as the real host signals.
 */
public final class FakeHostInterruptSignalContractTest
{
	/**
	 * Creates the test.
	 */
	public FakeHostInterruptSignalContractTest()
	{
	}

	/**
	 * Returns the contract over a fresh fake.
	 *
	 * @return the contract
	 */
	private static HostInterruptSignalContract contract()
	{
		FakeHostInterruptSignal signal = new FakeHostInterruptSignal();
		return new HostInterruptSignalContract(new HostInterruptSignalContract.Subject()
		{
			@Override
			public HostInterruptSignal port()
			{
				return signal;
			}

			@Override
			public void interrupt(Instant at)
			{
				signal.interrupt(at);
			}
		});
	}

	/**
	 * Verifies that a fake without a record reports no interrupt.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void reportsNoInterruptWithoutRecord() throws IOException
	{
		// Requirement: R-E10
		contract().reportsNoInterruptWithoutRecord();
	}

	/**
	 * Verifies strict timestamp comparison and repeated polls.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void comparesTimestampStrictly() throws IOException
	{
		// Requirement: R-E10
		contract().comparesTimestampStrictly();
	}

	/**
	 * Verifies that older records cannot erase a newer interrupt.
	 *
	 * @throws IOException if the check fails
	 */
	@Test
	public void retainsLatestTimestamp() throws IOException
	{
		// Requirement: R-E10
		contract().retainsLatestTimestamp();
	}

	/**
	 * Verifies that a null start is rejected.
	 *
	 * @throws IOException if the check fails
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullStart() throws IOException
	{
		// Requirement: R-E10
		contract().rejectsNullStart();
	}
}
