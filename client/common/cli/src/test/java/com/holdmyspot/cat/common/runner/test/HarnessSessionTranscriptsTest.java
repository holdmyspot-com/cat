package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
