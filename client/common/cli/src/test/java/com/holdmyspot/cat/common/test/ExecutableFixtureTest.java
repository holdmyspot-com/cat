package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.testng.SkipException;
import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Verifies {@link ExecutableFixture}.
 */
public final class ExecutableFixtureTest
{
	/**
	 * The name of the event that the JVM records for each write to a file.
	 */
	private static final String FILE_WRITE = "jdk.FileWrite";

	/**
	 * Creates the test fixture.
	 */
	public ExecutableFixtureTest()
	{
	}

	/**
	 * Verifies that a program that the fixture writes or copies runs at once, and that, on a file system with POSIX
	 * modes, the JVM writes neither program, so no process that the JVM starts meanwhile can inherit a descriptor
	 * that keeps one open for writing.
	 *
	 * @throws IOException          if a program cannot be written, run or recorded
	 * @throws InterruptedException if the thread is interrupted while a program runs
	 */
	@Test
	public void jvmWritesNoProgram() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories(); Recording recording = new Recording())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-programs-")).toAbsolutePath();
			if (!PortableFiles.hasModes(directory))
				throw new SkipException("The JVM writes programs itself on a file system without POSIX modes");
			recording.enable(FILE_WRITE).withThreshold(Duration.ZERO);
			recording.start();
			Path written = ExecutableFixture.write(directory.resolve("written"), "#!/bin/sh\necho written \"$1\"\n");
			Path copied = ExecutableFixture.copy(written, directory.resolve("copied"));
			recording.stop();
			Path dump = Files.createTempFile(cleanup.add(Files.createTempDirectory("cat-recording-")), "writes-",
				".jfr");
			recording.dump(dump);

			List<String> programWrites = new ArrayList<>();
			for (RecordedEvent event : RecordingFile.readAllEvents(dump))
			{
				// The dump also holds the events of any other recording that runs in the JVM
				if (!event.getEventType().getName().equals(FILE_WRITE))
					continue;
				String path = event.getString("path");
				if (path != null && Path.of(path).startsWith(directory))
					programWrites.add(path);
			}
			requireThat(programWrites, "programWrites").isEmpty();
			requireThat(run(written), "written").isEqualTo("written now\n");
			requireThat(run(copied), "copied").isEqualTo("written now\n");
		}
	}

	/**
	 * Runs a program with the argument {@code now}.
	 *
	 * @param program the program
	 * @return the program's standard output
	 * @throws IOException          if the program cannot be started, or it fails
	 * @throws InterruptedException if the thread is interrupted while the program runs
	 */
	private static String run(Path program) throws IOException, InterruptedException
	{
		try (Process process = new ProcessBuilder(program.toString(), "now").redirectErrorStream(true).start())
		{
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			requireThat(process.waitFor(), "status").isEqualTo(0);
			return output;
		}
	}
}
