package io.github.cowwoc.cat.claude.optimizer.test;

import io.github.cowwoc.cat.claude.ClaudeConfiguration;
import io.github.cowwoc.cat.claude.optimizer.TranscriptInterruptSignal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.fakes.test.ManualClock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.testng.annotations.Test;

/**
 * Tests {@link TranscriptInterruptSignal} against a transcript file that the test appends to.
 */
public final class TranscriptInterruptSignalTest
{
	private static final Instant START = Instant.parse("2026-10-01T07:55:00Z");
	private static final String INTERRUPT = """
		{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},\
		"timestamp":"%s"}""";

	/**
	 * Creates the test.
	 */
	public TranscriptInterruptSignalTest()
	{
	}

	/**
	 * Appends one line to a transcript.
	 *
	 * @param transcript the transcript
	 * @param line the line, without its terminator
	 * @throws IOException if the file cannot be written
	 */
	private static void append(Path transcript, String line) throws IOException
	{
		Files.writeString(transcript, line + "\n", UTF_8, CREATE, APPEND);
	}

	/**
	 * Verifies that an interrupt record that the host wrote after the start is reported, and that nothing is reported
	 * before it exists.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void reportsInterruptRecordedAfterStart() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-transcript-")).resolve("session.jsonl");
			ManualClock clock = new ManualClock(START);
			TranscriptInterruptSignal signal = new TranscriptInterruptSignal(transcript, clock);
			append(transcript, "{\"type\":\"assistant\",\"timestamp\":\"2026-10-01T07:55:01.000Z\"}");
			requireThat(signal.isInterruptedAfter(START), "beforeInterrupt").isFalse();

			clock.advance(Duration.ofSeconds(2));
			append(transcript, INTERRUPT.formatted("2026-10-01T07:55:02.000Z"));
			requireThat(signal.isInterruptedAfter(START), "afterInterrupt").isTrue();
			requireThat(signal.isInterruptedAfter(START), "stillInterrupted").isTrue();
		}
	}

	/**
	 * Verifies that an interrupt record from before the start is ignored.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void ignoresInterruptRecordedBeforeStart() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-transcript-")).resolve("session.jsonl");
			append(transcript, INTERRUPT.formatted("2026-10-01T07:54:59.999Z"));
			TranscriptInterruptSignal signal = new TranscriptInterruptSignal(transcript, new ManualClock(START));
			requireThat(signal.isInterruptedAfter(START), "interrupted").isFalse();
			requireThat(signal.isInterruptedAfter(START.minusSeconds(1)), "earlierStart").isTrue();
		}
	}

	/**
	 * Verifies that the prefix match accepts the tool-use wording and both content shapes, and that records of other
	 * types or with other text do not match.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void matchesOnlyUserInterruptPrefix() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-transcript-")).resolve("session.jsonl");
			ManualClock clock = new ManualClock(START);
			TranscriptInterruptSignal signal = new TranscriptInterruptSignal(transcript, clock);
			append(transcript, "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":[" +
				"{\"type\":\"text\",\"text\":\"[Request interrupted by user]\"}]}," +
				"\"timestamp\":\"2026-10-01T07:55:03.000Z\"}");
			append(transcript, "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"please continue\"}," +
				"\"timestamp\":\"2026-10-01T07:55:03.000Z\"}");
			append(transcript, "not json");
			requireThat(signal.isInterruptedAfter(START), "otherRecords").isFalse();

			append(transcript, "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":" +
				"\"[Request interrupted by user for tool use]\"},\"timestamp\":\"2026-10-01T07:55:04.000Z\"}");
			requireThat(signal.isInterruptedAfter(START), "stringContent").isTrue();
		}
	}

	/**
	 * Verifies that a record that the host has not finished writing is read once its line ends.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void waitsForCompleteLine() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-transcript-")).resolve("session.jsonl");
			TranscriptInterruptSignal signal = new TranscriptInterruptSignal(transcript, new ManualClock(START));
			String record = INTERRUPT.formatted("2026-10-01T07:55:05.000Z");
			Files.writeString(transcript, record.substring(0, 40), UTF_8, CREATE, APPEND);
			requireThat(signal.isInterruptedAfter(START), "partialRecord").isFalse();

			Files.writeString(transcript, record.substring(40) + "\n", UTF_8, APPEND);
			requireThat(signal.isInterruptedAfter(START), "completeRecord").isTrue();
		}
	}

	/**
	 * Verifies that a transcript that does not exist yet reports no interrupt and is read once it appears.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void missingTranscriptIsNotAnInterrupt() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path transcript = cleanup.add(Files.createTempDirectory("cat-transcript-")).resolve("session.jsonl");
			TranscriptInterruptSignal signal = new TranscriptInterruptSignal(transcript, new ManualClock(START));
			requireThat(signal.isInterruptedAfter(START), "missing").isFalse();
			append(transcript, INTERRUPT.formatted("2026-10-01T07:55:06.000Z"));
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
			Path transcript = cleanup.add(Files.createTempDirectory("cat-transcript-")).resolve("session.jsonl");
			ManualClock clock = new ManualClock(START);
			TranscriptInterruptSignal signal = new TranscriptInterruptSignal(transcript, clock);
			clock.advance(Duration.ofSeconds(5));
			append(transcript, "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":" +
				"\"[Request interrupted by user]\"}}");
			requireThat(signal.isInterruptedAfter(START), "unstamped").isTrue();
			requireThat(signal.isInterruptedAfter(START.plusSeconds(5)), "notAfterReadTime").isFalse();
		}
	}

	/**
	 * Verifies that the transcript is found under {@code projects/<project>/<session>.jsonl} of the configuration
	 * directory, and that the configuration directory defaults to {@code .claude} below the home directory.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void locatesTranscriptBySessionId() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-home-"));
			Path configuration = ClaudeConfiguration.resolveDirectory(home, null);
			requireThat(configuration, "default").isEqualTo(home.resolve(".claude"));
			String blank = "  ";
			requireThat(ClaudeConfiguration.resolveDirectory(home, blank), "blank").
				isEqualTo(configuration);
			requireThat(ClaudeConfiguration.resolveDirectory(home, "/custom"), "configured").
				isEqualTo(Path.of("/custom").toAbsolutePath());

			Path project = Files.createDirectories(configuration.resolve("projects").resolve("-work-fixture"));
			Files.createDirectories(configuration.resolve("projects").resolve("-work-other"));
			Path transcript = project.resolve("2d657f72-208e-4d11-b888-42ca36f133e7.jsonl");
			Files.writeString(transcript, "\n", UTF_8);
			requireThat(TranscriptInterruptSignal.locate(configuration, "2d657f72-208e-4d11-b888-42ca36f133e7"),
				"located").isEqualTo(Optional.of(transcript));
			requireThat(TranscriptInterruptSignal.locate(configuration, "unknown-session"), "unknown").
				isEqualTo(Optional.empty());
		}
	}
}
