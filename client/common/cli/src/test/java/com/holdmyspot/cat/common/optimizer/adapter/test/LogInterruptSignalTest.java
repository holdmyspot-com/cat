package com.holdmyspot.cat.common.optimizer.adapter.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.adapter.LogInterruptSignal;
import java.time.Instant;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests when an interrupt record of a host session's log counts as written.
 */
public final class LogInterruptSignalTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Instant NOW = Instant.parse("2026-10-01T07:55:05Z");

	/**
	 * Creates the test.
	 */
	public LogInterruptSignalTest()
	{
	}

	/**
	 * Supplies each record and the time at which it counts as written.
	 *
	 * @return the record and its time
	 */
	@DataProvider
	public Object[][] records()
	{
		return new Object[][]
			{
				{"{\"timestamp\":\"2026-10-01T07:55:02.000Z\"}", Instant.parse("2026-10-01T07:55:02Z")},
				// A record without a timestamp counts as written when it is read
				{"{\"type\":\"user\"}", NOW},
				// So does a record whose timestamp is not an instant, or not text
				{"{\"timestamp\":\"yesterday\"}", NOW},
				{"{\"timestamp\":1790841302}", NOW}
			};
	}

	/**
	 * A record is dated by its timestamp, or else by the time at which it is read.
	 *
	 * @param record  the record
	 * @param written the time at which it counts as written
	 */
	@Test(dataProvider = "records")
	public void recordIsDatedByStampOrReadTime(String record, Instant written)
	{
		requireThat(LogInterruptSignal.recordedAt(JSON.readTree(record), NOW), "recordedAt").isEqualTo(written);
	}
}
