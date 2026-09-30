package io.github.cowwoc.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * Verifies exact parent transcript correlation with small in-memory records and temporary files.
 */
public final class HostToolModelTest
{
	private static final String PARENT = "{\"type\":\"assistant\",\"sessionId\":\"s\",\"isSidechain\":false," +
		"\"message\":{\"model\":\"model-a\",\"content\":[{\"type\":\"tool_use\",\"id\":\"t\"}]}}";

	/**
	 * Creates the test fixture.
	 */
	public HostToolModelTest()
	{
	}

	/**
	 * Only the exact parent session, tool and explicit non-sidechain record supplies a model.
	 */
	@Test
	public void selectsExactParentRecord() throws IOException
	{
		String other = PARENT.replace("\"s\"", "\"other\"");
		String otherTool = PARENT.replace("\"t\"", "\"other-tool\"");
		requireThat(read(other + "\n" + otherTool + "\n" + PARENT, true), "exactParent").
			isEqualTo("model-a");
		for (String excluded : new String[]{PARENT.replace("false", "true"),
			PARENT.replace(",\"isSidechain\":false", ""), PARENT.replace("false", "\"false\"")})
			requireThat(read(excluded, true), "excludedRecord").isNull();
	}

	/**
	 * The exact parent record returns its native model.
	 */
	@Test
	public void readsParentModel() throws IOException
	{
		requireThat(read(PARENT, true), "parentModel").isEqualTo("model-a");
	}

	/**
	 * A matching parent record without a model fails.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*missing a nonblank model.*")
	public void rejectsMissingModel() throws IOException
	{
		read(PARENT.replace("\"model\":\"model-a\",", ""), true);
	}

	/**
	 * A matching parent record with a blank model fails.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*missing a nonblank model.*")
	public void rejectsBlankModel() throws IOException
	{
		read(PARENT.replace("model-a", " "), true);
	}

	/**
	 * A matching parent record with a nontext model fails.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*missing a nonblank model.*")
	public void rejectsNontextModel() throws IOException
	{
		read(PARENT.replace("\"model-a\"", "7"), true);
	}

	/**
	 * Matching parent records cannot claim different models for one tool use.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*conflicting models.*")
	public void rejectsConflictingModels() throws IOException
	{
		read(PARENT + "\n" + PARENT.replace("model-a", "model-b"), true);
	}

	/**
	 * Duplicate JSON fields are rejected by the strict transcript mapper.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*malformed complete record.*")
	public void rejectsDuplicateFields() throws IOException
	{
		read(PARENT.replace("\"isSidechain\":false", "\"isSidechain\":false,\"isSidechain\":false"), true);
	}

	/**
	 * Complete malformed records fail while only an incomplete final record remains pending.
	 */
	@Test
	public void distinguishesIncompleteTail() throws IOException
	{
		requireThat(read("{", false), "pendingTail").isNull();
		requireThat(read(PARENT, false), "completeJsonWithoutNewline").isEqualTo("model-a");
	}

	/**
	 * A malformed final record marked complete fails.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*malformed complete record.*")
	public void rejectsCompleteMalformedRecord() throws IOException
	{
		read("{", true);
	}

	/**
	 * A malformed nonfinal record fails even when the final record is incomplete.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*malformed complete record.*")
	public void rejectsMalformedNonfinalRecord() throws IOException
	{
		read("{\n" + PARENT, false);
	}

	/**
	 * An append after extent capture is excluded from the current channel read attempt.
	 */
	@Test
	public void readsOnlyCapturedExtent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("host-tool-model"));
			Path transcript = directory.resolve("transcript.jsonl");
			Files.writeString(transcript, PARENT.replace("\"s\"", "\"other\"") + "\n");
			try (FileChannel channel = FileChannel.open(transcript, StandardOpenOption.READ))
			{
				long capturedSize = channel.size();
				Files.writeString(transcript, "{", StandardOpenOption.APPEND);
				requireThat(HostToolModel.readAttempt(channel, "s", "t", capturedSize), "capturedRecords").
					isEqualTo(Optional.empty());
			}
		}
	}

	/**
	 * Invalid UTF-8 bytes fail using the decoder's reporting behavior.
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsInvalidUtf8() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("host-tool-model"));
			Path transcript = directory.resolve("transcript.jsonl");
			Files.write(transcript, new byte[]{(byte) 0xFF});
			HostToolModel.awaitModel(transcript, "s", "t", Clock.systemUTC(), delay -> {});
		}
	}

	/**
	 * Waits for a delayed matching record already in an existing transcript.
	 */
	@Test
	public void waitsForTranscriptModel() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("host-tool-model"));
			Path transcript = directory.resolve("transcript.jsonl");
			FakeClock clock = new FakeClock();
			AtomicInteger pauses = new AtomicInteger();
			Files.writeString(transcript, PARENT.replace("\"s\"", "\"other\"") + "\n");
			String model = HostToolModel.awaitModel(transcript, "s", "t", clock, delay ->
			{
				clock.advance(delay);
				if (pauses.incrementAndGet() == 2)
				{
					try
					{
						Files.writeString(transcript, PARENT + "\n", StandardOpenOption.APPEND);
					}
					catch (IOException e)
					{
						throw new IllegalStateException(e);
					}
				}
			});
			requireThat(model, "delayedModel").isEqualTo("model-a");
		}
	}

	/**
	 * An existing transcript without the requested parent record times out.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Timed out waiting.*")
	public void timesOutOnExistingTranscript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("host-tool-model"));
			Path transcript = directory.resolve("transcript.jsonl");
			Files.writeString(transcript, PARENT.replace("\"s\"", "\"other\"") + "\n");
			FakeClock clock = new FakeClock();
			HostToolModel.awaitModel(transcript, "s", "t", clock, clock::advance);
		}
	}

	/**
	 * A missing transcript remains pending until the lookup deadline.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Timed out waiting.*")
	public void timesOutOnMissingTranscript() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("host-tool-model"));
			FakeClock clock = new FakeClock();
			HostToolModel.awaitModel(directory.resolve("missing.jsonl"), "s", "t", clock, clock::advance);
		}
	}

	/**
	 * Null transcript path is rejected before the file system is read.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullTranscript() throws IOException
	{
		HostToolModel.awaitModel(null, "s", "t", Clock.systemUTC(), delay -> {});
	}

	/**
	 * Null session identity is rejected before the file system is read.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullSession() throws IOException
	{
		HostToolModel.awaitModel(Path.of("missing.jsonl"), null, "t", Clock.systemUTC(), delay -> {});
	}

	/**
	 * Null tool identity is rejected before the file system is read.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullToolUse() throws IOException
	{
		HostToolModel.awaitModel(Path.of("missing.jsonl"), "s", null, Clock.systemUTC(), delay -> {});
	}

	/**
	 * Blank session identity is rejected before the file system is read.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankSession() throws IOException
	{
		HostToolModel.awaitModel(Path.of("missing.jsonl"), " ", "t", Clock.systemUTC(), delay -> {});
	}

	/**
	 * Blank tool identity is rejected before the file system is read.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankToolUse() throws IOException
	{
		HostToolModel.awaitModel(Path.of("missing.jsonl"), "s", " ", Clock.systemUTC(), delay -> {});
	}

	/**
	 * Null clock is rejected before the file system is read.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullClock() throws IOException
	{
		HostToolModel.awaitModel(Path.of("missing.jsonl"), "s", "t", null, delay -> {});
	}

	/**
	 * Null pause operation is rejected before the file system is read.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullPause() throws IOException
	{
		HostToolModel.awaitModel(Path.of("missing.jsonl"), "s", "t", Clock.systemUTC(), null);
	}

	/**
	 * Null record reader is rejected by the direct record matcher.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullReader() throws IOException
	{
		HostToolModel.findModel(null, "s", "t", true);
	}

	/**
	 * Null record session is rejected by the direct record matcher.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullRecordSession() throws IOException
	{
		HostToolModel.findModel(reader(""), null, "t", true);
	}

	/**
	 * Null record tool identity is rejected by the direct record matcher.
	 */
	@Test(expectedExceptions = NullPointerException.class)
	public void rejectsNullRecordToolUse() throws IOException
	{
		HostToolModel.findModel(reader(""), "s", null, true);
	}

	/**
	 * Blank record session is rejected by the direct record matcher.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankRecordSession() throws IOException
	{
		HostToolModel.findModel(reader(""), " ", "t", true);
	}

	/**
	 * Blank record tool identity is rejected by the direct record matcher.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsBlankRecordToolUse() throws IOException
	{
		HostToolModel.findModel(reader(""), "s", " ", true);
	}

	/**
	 * Interrupted polling restores the interrupt flag before reporting failure.
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Interrupted while waiting.*")
	public void restoresInterruptFlag() throws IOException
	{
		Thread.interrupted();
		try
		{
			HostToolModel.awaitModel(Path.of("missing.jsonl"), "s", "t", new FakeClock(), delay ->
			{
				throw new InterruptedException();
			});
		}
		finally
		{
			requireThat(Thread.currentThread().isInterrupted(), "interruptRestored").isTrue();
			Thread.interrupted();
		}
	}

	/**
	 * Runs one record fixture through the production matcher.
	 *
	 * @param records JSONL record text
	 * @param complete whether the final record is complete
	 * @return selected model or null
	 * @throws IOException if a complete record fails validation
	 */
	private static String read(String records, boolean complete) throws IOException
	{
		try (BufferedReader reader = reader(records))
		{
			return HostToolModel.findModel(reader, "s", "t", complete).orElse(null);
		}
	}

	/**
	 * Creates a reader for an in-memory fixture.
	 *
	 * @param value fixture text
	 * @return buffered reader
	 */
	private static BufferedReader reader(String value)
	{
		return new BufferedReader(new StringReader(value));
	}

	/**
	 * Provides controllable time to polling tests.
	 */
	private static final class FakeClock extends Clock
	{
		private Instant now = Instant.EPOCH;

		/**
		 * Moves fake time by the requested interval.
		 *
		 * @param duration elapsed duration
		 */
		void advance(Duration duration)
		{
			now = now.plus(duration);
		}

		/**
		 * Returns elapsed fake time from its origin.
		 *
		 * @return elapsed duration
		 */
		Duration elapsed()
		{
			return Duration.between(Instant.EPOCH, now);
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			return this;
		}

		@Override
		public Instant instant()
		{
			return now;
		}
	}
}
