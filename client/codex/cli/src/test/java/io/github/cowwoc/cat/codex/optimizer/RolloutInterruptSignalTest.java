package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.fakes.ManualClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.testng.annotations.Test;

/**
 * Tests {@link RolloutInterruptSignal} against a rollout file that the test appends to.
 */
public final class RolloutInterruptSignalTest
{
	private static final Instant START = Instant.parse("2026-10-01T07:56:00Z");
	private static final String ABORTED = """
		{"timestamp":"%s","ordinal":40,"type":"event_msg","payload":{"type":"turn_aborted",\
		"turn_id":"01a0f676-b871-74c2-9e1f-ecbf1f2cd080","reason":"interrupted"}}""";

	/**
	 * Creates the test.
	 */
	public RolloutInterruptSignalTest()
	{
	}

	/**
	 * Appends one line to a rollout.
	 *
	 * @param rollout the rollout
	 * @param line the line, without its terminator
	 * @throws IOException if the file cannot be written
	 */
	private static void append(Path rollout, String line) throws IOException
	{
		Files.writeString(rollout, line + "\n", UTF_8, CREATE, APPEND);
	}

	/**
	 * Verifies that a {@code turn_aborted} event written after the start is reported, and that nothing is reported
	 * before it exists.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void reportsTurnAbortedAfterStart() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path rollout = cleanup.add(Files.createTempDirectory("cat-rollout-")).resolve("rollout.jsonl");
			ManualClock clock = new ManualClock(START);
			RolloutInterruptSignal signal = new RolloutInterruptSignal(rollout, clock);
			append(rollout, "{\"timestamp\":\"2026-10-01T07:56:01.000Z\",\"type\":\"event_msg\"," +
				"\"payload\":{\"type\":\"task_started\"}}");
			requireThat(signal.isInterruptedAfter(START), "beforeAbort").isFalse();

			clock.advance(Duration.ofSeconds(3));
			append(rollout, ABORTED.formatted("2026-10-01T07:56:03.000Z"));
			requireThat(signal.isInterruptedAfter(START), "afterAbort").isTrue();
			requireThat(signal.isInterruptedAfter(START), "stillAborted").isTrue();
		}
	}

	/**
	 * Verifies that a {@code turn_aborted} event from before the start is ignored.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void ignoresTurnAbortedBeforeStart() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path rollout = cleanup.add(Files.createTempDirectory("cat-rollout-")).resolve("rollout.jsonl");
			append(rollout, ABORTED.formatted("2026-10-01T07:55:59.000Z"));
			RolloutInterruptSignal signal = new RolloutInterruptSignal(rollout, new ManualClock(START));
			requireThat(signal.isInterruptedAfter(START), "interrupted").isFalse();
		}
	}

	/**
	 * Verifies that only the event record matches. The developer message that Codex adds to the conversation after an
	 * abort quotes the same word and must not count by itself.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void ignoresRecordsThatOnlyMentionTheAbort() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path rollout = cleanup.add(Files.createTempDirectory("cat-rollout-")).resolve("rollout.jsonl");
			RolloutInterruptSignal signal = new RolloutInterruptSignal(rollout, new ManualClock(START));
			append(rollout, "{\"timestamp\":\"2026-10-01T07:56:04.000Z\",\"type\":\"response_item\",\"payload\":" +
				"{\"type\":\"message\",\"role\":\"developer\",\"content\":[{\"type\":\"input_text\",\"text\":" +
				"\"<turn_aborted>\\nThe previous turn was interrupted on purpose.\\n</turn_aborted>\"}]}}");
			append(rollout, "not json");
			requireThat(signal.isInterruptedAfter(START), "mention").isFalse();
		}
	}

	/**
	 * Verifies that a record that Codex has not finished writing is read once its line ends.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void waitsForCompleteLine() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path rollout = cleanup.add(Files.createTempDirectory("cat-rollout-")).resolve("rollout.jsonl");
			RolloutInterruptSignal signal = new RolloutInterruptSignal(rollout, new ManualClock(START));
			String record = ABORTED.formatted("2026-10-01T07:56:05.000Z");
			Files.writeString(rollout, record.substring(0, 50), UTF_8, CREATE, APPEND);
			requireThat(signal.isInterruptedAfter(START), "partialRecord").isFalse();

			Files.writeString(rollout, record.substring(50) + "\n", UTF_8, APPEND);
			requireThat(signal.isInterruptedAfter(START), "completeRecord").isTrue();
		}
	}

	/**
	 * Verifies that a rollout that does not exist yet reports no interrupt and is read once it appears.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void missingRolloutIsNotAnInterrupt() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path rollout = cleanup.add(Files.createTempDirectory("cat-rollout-")).resolve("rollout.jsonl");
			RolloutInterruptSignal signal = new RolloutInterruptSignal(rollout, new ManualClock(START));
			requireThat(signal.isInterruptedAfter(START), "missing").isFalse();
			append(rollout, ABORTED.formatted("2026-10-01T07:56:06.000Z"));
			requireThat(signal.isInterruptedAfter(START), "created").isTrue();
		}
	}

	/**
	 * Verifies that a matching record without a timestamp counts as written when the signal first read it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void attributesUnstampedRecordToReadTime() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path rollout = cleanup.add(Files.createTempDirectory("cat-rollout-")).resolve("rollout.jsonl");
			ManualClock clock = new ManualClock(START);
			RolloutInterruptSignal signal = new RolloutInterruptSignal(rollout, clock);
			clock.advance(Duration.ofSeconds(5));
			append(rollout, "{\"type\":\"event_msg\",\"payload\":{\"type\":\"turn_aborted\"}}");
			requireThat(signal.isInterruptedAfter(START), "unstamped").isTrue();
			requireThat(signal.isInterruptedAfter(START.plusSeconds(5)), "notAfterReadTime").isFalse();
		}
	}

	/**
	 * Verifies that the rollout is found as {@code sessions/**}{@code /rollout-*-<thread>.jsonl} below the Codex home,
	 * and that the Codex home defaults to {@code .codex} below the home directory.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void locatesRolloutByThreadId() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			Path codexHome = RolloutInterruptSignal.codexHome(home, null);
			requireThat(codexHome, "default").isEqualTo(home.resolve(".codex"));
			requireThat(RolloutInterruptSignal.codexHome(home, ""), "blank").isEqualTo(codexHome);
			requireThat(RolloutInterruptSignal.codexHome(home, "/custom"), "configured").isEqualTo(Path.of("/custom"));

			Path day = Files.createDirectories(codexHome.resolve("sessions").resolve("2026").resolve("10").
				resolve("01"));
			Path rollout = day.resolve("rollout-2026-10-01T03-56-10-01a0f676-b854-7202-a905-d64485acdc91.jsonl");
			Files.writeString(rollout, "\n", UTF_8);
			Files.writeString(day.resolve("rollout-2026-10-01T03-56-09-01a0f676-b37f-7253-9fc2-55c975b8df47.jsonl"),
				"\n", UTF_8);
			requireThat(RolloutInterruptSignal.locate(codexHome, "01a0f676-b854-7202-a905-d64485acdc91"), "located").
				isEqualTo(Optional.of(rollout));
			requireThat(RolloutInterruptSignal.locate(codexHome, "unknown-thread"), "unknown").
				isEqualTo(Optional.empty());
		}
	}
}
