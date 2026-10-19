package com.holdmyspot.cat.codex.optimizer.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import com.holdmyspot.cat.codex.optimizer.RolloutInterruptSignal;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import com.holdmyspot.cat.common.optimizer.port.contract.test.HostInterruptSignalContract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.testng.annotations.Test;

/**
 * Runs the shared {@link HostInterruptSignalContract} against {@link RolloutInterruptSignal} using test-owned host
 * logs.
 */
public final class RolloutInterruptSignalContractTest
{
	/**
	 * Creates the test.
	 */
	public RolloutInterruptSignalContractTest()
	{
	}

	/**
	 * Returns the contract over a signal whose host records are written to the supplied file.
	 *
	 * @param log the test-owned host log
	 * @return the contract
	 */
	private static HostInterruptSignalContract contract(Path log)
	{
		Clock clock = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC);
		HostInterruptSignal signal = new RolloutInterruptSignal(log, clock);
		return new HostInterruptSignalContract(new HostInterruptSignalContract.Subject()
		{
			@Override
			public HostInterruptSignal port()
			{
				return signal;
			}

			@Override
			public void interrupt(Instant at) throws IOException
			{
				String record = """
					{"timestamp":"%s","type":"event_msg","payload":{"type":"turn_aborted"}}
					""".formatted(at);
				Files.writeString(log, record, UTF_8, CREATE, APPEND);
			}
		});
	}

	/**
	 * Verifies that a host log without an interrupt reports none.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test
	public void reportsNoInterruptWithoutRecord() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = Path.of(System.getProperty("cat.work.temp"));
			Files.createDirectories(temporaryRoot);
			Path log = cleanup.add(Files.createTempDirectory(temporaryRoot, "cat-interrupt-contract-")).
				resolve("rollout.jsonl");
			contract(log).reportsNoInterruptWithoutRecord();
		}
	}

	/**
	 * Verifies that host timestamps are compared strictly and survive repeated polls.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test
	public void comparesTimestampStrictly() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = Path.of(System.getProperty("cat.work.temp"));
			Files.createDirectories(temporaryRoot);
			Path log = cleanup.add(Files.createTempDirectory(temporaryRoot, "cat-interrupt-contract-")).
				resolve("rollout.jsonl");
			contract(log).comparesTimestampStrictly();
		}
	}

	/**
	 * Verifies that older host records cannot erase a later interrupt.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test
	public void retainsLatestTimestamp() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = Path.of(System.getProperty("cat.work.temp"));
			Files.createDirectories(temporaryRoot);
			Path log = cleanup.add(Files.createTempDirectory(temporaryRoot, "cat-interrupt-contract-")).
				resolve("rollout.jsonl");
			contract(log).retainsLatestTimestamp();
		}
	}

	/**
	 * Verifies that a null start is rejected.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullStart() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporaryRoot = Path.of(System.getProperty("cat.work.temp"));
			Files.createDirectories(temporaryRoot);
			Path log = cleanup.add(Files.createTempDirectory(temporaryRoot, "cat-interrupt-contract-")).
				resolve("rollout.jsonl");
			contract(log).rejectsNullStart();
		}
	}
}
