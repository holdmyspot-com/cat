package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests how {@link Yielding#start(ProcessBuilder, ExecutableFiles)} starts a program that a test has just written.
 * <p>
 * The JVM opens files without close-on-exec, so a process that another test starts while this test writes a program
 * inherits the open file and holds it open for writing until it closes the descriptors that it inherited. Linux
 * refuses to run the program meanwhile with {@code ETXTBSY} ("Text file busy"). Each test stands in for that child
 * with a write channel that it keeps open on the program, so that the refusal is the operating system's own.
 */
public final class YieldingTest
{
	/**
	 * Creates the test.
	 */
	public YieldingTest()
	{
	}

	/**
	 * Verifies that a start that Linux refuses while a process holds the program open for writing succeeds once that
	 * process closes it, which the pause between attempts does here, as the inherited descriptor would close during
	 * the retries.
	 *
	 * @throws IOException          if the program cannot be written or run
	 * @throws InterruptedException if the thread is interrupted while the program runs
	 */
	@Test
	public void startRunsProgramOnceItsWriterCloses() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path program = program(cleanup);
			// Stands in for the descriptor that a child process that another test started inherited during the write
			try (FileChannel inherited = FileChannel.open(program, StandardOpenOption.WRITE))
			{
				ExecutableFiles executables = new ExecutableFiles(inherited::close, new ManualClock(Instant.EPOCH));

				try (Process process = Yielding.start(new ProcessBuilder(program.toString(), "now"), executables))
				{
					String output = new String(process.getInputStream().readAllBytes(), UTF_8);
					requireThat(process.waitFor(), "status").isEqualTo(0);
					requireThat(output, "output").isEqualTo("ran now\n");
				}
				// Only a refused start pauses, so a closed channel shows that the first start was refused
				requireThat(inherited.isOpen(), "inherited.isOpen").isFalse();
			}
		}
	}

	/**
	 * Verifies that a start keeps failing with the operating system's refusal once the retry limit passes while a
	 * process holds the program open for writing.
	 *
	 * @throws IOException if the program cannot be written, or, as expected, cannot be started
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Text file busy.*")
	public void startFailsWhileWriterStaysOpen() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path program = program(cleanup);
			// Stands in for a child process that never closes the descriptor that it inherited during the write
			try (FileChannel _ = FileChannel.open(program, StandardOpenOption.WRITE))
			{
				ManualClock clock = new ManualClock(Instant.EPOCH);
				ExecutableFiles executables = new ExecutableFiles(() -> clock.advance(Duration.ofMillis(400)), clock);

				Yielding.start(new ProcessBuilder(program.toString(), "now"), executables).destroyForcibly();
			}
		}
	}

	/**
	 * Writes a program that prints {@code ran} followed by its argument.
	 *
	 * @param cleanup removes the program's directory
	 * @return the program
	 * @throws IOException   if the program cannot be written
	 * @throws SkipException if the platform is not Linux, the only platform that refuses to run a file that a process
	 *                       holds open for writing
	 */
	private static Path program(TemporaryDirectories cleanup) throws IOException
	{
		if (!Platform.current().isLinux())
			throw new SkipException("Only Linux refuses to run a file that a process holds open for writing");
		Path directory = cleanup.add(Files.createTempDirectory("yielding-start-"));
		Path program = Files.writeString(directory.resolve("program"), "#!/bin/sh\necho ran \"$1\"\n");
		Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwx------"));
		return program;
	}
}
