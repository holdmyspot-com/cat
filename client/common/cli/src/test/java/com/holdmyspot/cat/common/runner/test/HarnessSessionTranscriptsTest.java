package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.testng.annotations.Test;

/**
 * Tests {@link HarnessSessionTranscripts}.
 */
public final class HarnessSessionTranscriptsTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessSessionTranscriptsTest()
	{
	}

	/**
	 * Verifies that the copy holds every matching file below the source, however deep, side by side under its own
	 * name, and none of the other files.
	 *
	 * @throws IOException if the fixture cannot be written or read
	 */
	@Test
	public void copiesMatchingFilesBesideEachOther() throws IOException
	{
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-session-transcripts-"));
			Path source = Files.createDirectories(root.resolve("source/session/subagents"));
			Files.writeString(root.resolve("source/session.jsonl"), "{\"root\":true}\n", UTF_8);
			Files.writeString(source.resolve("agent-1.jsonl"), "{\"child\":true}\n", UTF_8);
			Files.writeString(root.resolve("source/notes.txt"), "not a transcript\n", UTF_8);
			Path destination = root.resolve("artifacts/session-transcripts");

			new HarnessSessionTranscripts(root.resolve("source"), destination, name -> name.endsWith(".jsonl")).copy();

			try (Stream<Path> files = Files.list(destination))
			{
				requireThat(files.map(file -> file.getFileName().toString()).sorted().toList(), "copies").
					isEqualTo(List.of("agent-1.jsonl", "session.jsonl"));
			}
			requireThat(Files.readString(destination.resolve("agent-1.jsonl"), UTF_8), "child").
				isEqualTo("{\"child\":true}\n");
		}
	}

	/**
	 * Verifies that a source that does not exist still leaves an empty destination, so that a session that wrote no
	 * transcript reads as having none.
	 *
	 * @throws IOException if the fixture cannot be created or listed
	 */
	@Test
	public void createsDestinationWithoutSource() throws IOException
	{
		// Requirement: R-I7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-session-transcripts-"));
			Path destination = root.resolve("artifacts/session-transcripts");

			new HarnessSessionTranscripts(root.resolve("missing"), destination, _ -> true).copy();

			try (Stream<Path> files = Files.list(destination))
			{
				requireThat(files.toList(), "copies").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a later snapshot publishes appended records once, including bytes that complete split UTF-8 input.
	 *
	 * @throws IOException if the fixture or snapshot cannot be written or read
	 */
	@Test
	public void withholdsUnfinishedTailAcrossSnapshots() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-live-transcripts-"));
			Path source = Files.createDirectories(root.resolve("home/sessions/subagents"));
			Path nativeRoot = root.resolve("home/sessions/rollout-root.jsonl");
			Path nativeChild = source.resolve("rollout-child.jsonl");
			Path empty = source.resolve("rollout-empty.jsonl");
			Path lineFeed = source.resolve("rollout-line-feed.jsonl");
			byte[] euro = "€".getBytes(UTF_8);
			Files.write(nativeRoot, concat("root\nunfinished-".getBytes(UTF_8), Arrays.copyOf(euro, 1)));
			Files.write(nativeChild, "\nchild-unfinished".getBytes(UTF_8));
			Files.write(empty, new byte[0]);
			Files.write(lineFeed, new byte[]{'\n'});
			Path destination = root.resolve("artifacts/session-transcripts");
			HarnessSessionTranscripts transcripts = transcripts(root, destination);

			transcripts.copyCompletedLines(Yielding.fileReplacer());

			requireThat(Files.readAllBytes(destination.resolve("rollout-root.jsonl")), "rootPrefix").
				isEqualTo("root\n".getBytes(UTF_8));
			requireThat(Files.readString(destination.resolve("rollout-child.jsonl"), UTF_8), "childPrefix").
				isEqualTo("\n");
			requireThat(Files.size(destination.resolve("rollout-empty.jsonl")), "emptyPrefix").isEqualTo(0L);
			requireThat(Files.readString(destination.resolve("rollout-line-feed.jsonl"), UTF_8), "lineFeedPrefix").
				isEqualTo("\n");

			Files.write(nativeRoot, concat(Arrays.copyOfRange(euro, 1, euro.length), "-finished\n".getBytes(UTF_8)),
				java.nio.file.StandardOpenOption.APPEND);
			Files.write(nativeChild, "child-finished\n".getBytes(UTF_8), java.nio.file.StandardOpenOption.APPEND);
			transcripts.copyCompletedLines(Yielding.fileReplacer());

			requireThat(Files.readString(destination.resolve("rollout-root.jsonl"), UTF_8), "rootComplete").
				isEqualTo("root\nunfinished-€-finished\n");
			requireThat(Files.readString(destination.resolve("rollout-child.jsonl"), UTF_8), "childComplete").
				isEqualTo("\nchild-unfinishedchild-finished\n");
		}
	}

	/**
	 * Verifies that live copies retain malformed complete lines while final copies retain the unterminated tail.
	 *
	 * @throws IOException if the fixture or copy cannot be written or read
	 */
	@Test
	public void preservesMalformedCompletedAndFinalBytes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-malformed-transcripts-"));
			Path source = Files.createDirectories(root.resolve("home/sessions"));
			Path rollout = Files.write(source.resolve("rollout-root.jsonl"), "not-json\nunfinished".getBytes(UTF_8));
			Path destination = root.resolve("live");
			HarnessSessionTranscripts transcripts = transcripts(root, destination);

			transcripts.copyCompletedLines(Yielding.fileReplacer());

			requireThat(Files.readAllBytes(destination.resolve("rollout-root.jsonl")), "liveMalformed").
				isEqualTo("not-json\n".getBytes(UTF_8));
			Path finalDestination = root.resolve("final");
			new HarnessSessionTranscripts(source, finalDestination, name -> name.endsWith(".jsonl")).copy();
			requireThat(Files.readAllBytes(finalDestination.resolve("rollout-root.jsonl")), "finalMalformed").
				isEqualTo(Files.readAllBytes(rollout));
		}
	}

	/**
	 * Verifies that a source failure preserves the previous copy and removes its temporary snapshot.
	 *
	 * @throws IOException if the fixture cannot be created or read
	 */
	@Test
	public void failedSnapshotPreservesEarlierCopy() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-failed-transcript-copy-"));
			Path source = Files.createDirectories(root.resolve("home/sessions"));
			Path transcript = Files.writeString(source.resolve("rollout-root.jsonl"), "new\n");
			Path destination = Files.createDirectories(root.resolve("copy"));
			Path previous = Files.writeString(destination.resolve("rollout-root.jsonl"), "previous\n");
			AtomicBoolean removed = new AtomicBoolean();
			HarnessSessionTranscripts transcripts = new HarnessSessionTranscripts(source, destination, name ->
			{
				if (removed.compareAndSet(false, true))
				{
					try
					{
						Files.delete(transcript);
					}
					catch (IOException e)
					{
						throw new java.io.UncheckedIOException(e);
					}
				}
				return true;
			});
			IOException failure = null;
			try
			{
				transcripts.copyCompletedLines(Yielding.fileReplacer());
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(failure.getMessage(), "message").contains(transcript.toString());
			requireThat(failure.getCause(), "cause").isInstanceOf(java.nio.file.NoSuchFileException.class);
			requireThat(Files.readString(previous), "previousCopy").isEqualTo("previous\n");
			try (Stream<Path> files = Files.list(destination))
			{
				requireThat(files.map(path -> path.getFileName().toString()).toList(), "destinationFiles").
					isEqualTo(List.of("rollout-root.jsonl"));
			}
		}
	}

	/**
	 * Verifies that large complete records and unfinished tails are copied with bounded content memory.
	 *
	 * @throws IOException if the fixture or snapshot cannot be written or read
	 */
	@Test
	public void keepsLongCompleteRecords() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-long-transcript-"));
			Path source = Files.createDirectories(root.resolve("home/sessions"));
			Path rollout = source.resolve("rollout-root.jsonl");
			byte[] record = ("record-".repeat(131_072) + "\n").getBytes(UTF_8);
			byte[] tail = "unfinished-".repeat(524_288).getBytes(UTF_8);
			Files.write(rollout, concat(record, tail));
			Path destination = root.resolve("copy");

			transcripts(root, destination).copyCompletedLines(Yielding.fileReplacer());

			requireThat(Files.readAllBytes(destination.resolve("rollout-root.jsonl")), "completeRecord").
				isEqualTo(record);
		}
	}

	/**
	 * Creates a native transcript locator for the test fixture.
	 *
	 * @param root the fixture root
	 * @param destination the copied transcript directory
	 * @return the locator
	 */
	private static HarnessSessionTranscripts transcripts(Path root, Path destination)
	{
		return new HarnessSessionTranscripts(root.resolve("home/sessions"), destination,
			name -> name.startsWith("rollout-") && name.endsWith(".jsonl"));
	}

	/**
	 * Joins byte arrays without decoding their contents.
	 *
	 * @param first the first bytes
	 * @param second the appended bytes
	 * @return the joined bytes
	 */
	private static byte[] concat(byte[] first, byte[] second)
	{
		byte[] combined = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, combined, first.length, second.length);
		return combined;
	}
}
