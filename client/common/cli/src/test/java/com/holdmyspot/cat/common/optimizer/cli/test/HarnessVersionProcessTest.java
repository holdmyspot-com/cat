package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.cli.HarnessVersion;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests the launcher that {@link HarnessVersion#process(ExecutableFiles)} returns, which starts a real process.
 */
public final class HarnessVersionProcessTest
{
	/**
	 * Creates the test.
	 */
	public HarnessVersionProcessTest()
	{
	}

	/**
	 * Verifies that the launcher starts a harness by a path without an extension, which on Windows names the batch file
	 * that npm installs, such as {@code codex.cmd}.
	 *
	 * @throws IOException if the test's files cannot be written
	 */
	@Test
	public void startsHarnessByPathWithoutExtension() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path programs = cleanup.add(Files.createTempDirectory("version-programs-")).
				toAbsolutePath();
			if (Platform.current().isWindows())
				Files.writeString(programs.resolve("harness.cmd"), "@echo harness 1.2.3\r\n");
			else
			{
				Path script = Files.writeString(programs.resolve("harness"), "#!/bin/sh\necho harness 1.2.3\n");
				requireThat(script.toFile().setExecutable(true, false), "executable").isTrue();
			}

			String version = HarnessVersion.read(programs.resolve("harness").toString(),
				HarnessVersion.process(Yielding.executableFiles()));
			requireThat(version, "version").isEqualTo("harness 1.2.3");
		}
	}

	/**
	 * Verifies that the launcher reads the version of a harness that Linux refuses to run while a process holds it open
	 * for writing, once that process closes it.
	 * <p>
	 * The JVM opens files without close-on-exec, so a process that another thread starts while CAT writes a harness
	 * inherits the open file until it closes the descriptors that it inherited. A write channel that the test keeps
	 * open stands in for that process, and the pause between attempts closes it, as the inherited descriptor would close
	 * during the retries.
	 *
	 * @throws IOException          if the test's files cannot be written or the harness cannot run
	 * @throws InterruptedException if the thread is interrupted while the harness runs
	 */
	@Test
	public void readsVersionOnceHarnessWriterCloses() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harness = linuxHarness(cleanup);
			try (FileChannel inherited = FileChannel.open(harness, StandardOpenOption.WRITE))
			{
				ExecutableFiles executables = new ExecutableFiles(inherited::close, new ManualClock(Instant.EPOCH));

				HarnessVersion.Output output = HarnessVersion.process(executables).run(List.of(harness.toString(),
					"--version"));

				requireThat(output.stdout(), "stdout").isEqualTo("harness 1.2.3\n");
				// Only a refused start pauses, so a closed channel shows that the first start was refused
				requireThat(inherited.isOpen(), "inherited.isOpen").isFalse();
			}
		}
	}

	/**
	 * Verifies that the launcher fails with the operating system's refusal once the retry limit passes while a process
	 * holds the harness open for writing.
	 *
	 * @throws IOException          if the test's files cannot be written, or, as expected, the harness cannot start
	 * @throws InterruptedException if the thread is interrupted while the harness runs
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = ".*Text file busy.*")
	public void failsWhileHarnessWriterStaysOpen() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path harness = linuxHarness(cleanup);
			// Stands in for a process that never closes the descriptor that it inherited while CAT wrote the harness
			try (FileChannel _ = FileChannel.open(harness, StandardOpenOption.WRITE))
			{
				ManualClock clock = new ManualClock(Instant.EPOCH);
				ExecutableFiles executables = new ExecutableFiles(() -> clock.advance(Duration.ofMillis(400)), clock);

				HarnessVersion.process(executables).run(List.of(harness.toString(), "--version"));
			}
		}
	}

	/**
	 * Writes a harness that prints its version.
	 *
	 * @param cleanup removes the harness's directory
	 * @return the harness
	 * @throws IOException   if the harness cannot be written
	 * @throws SkipException if the platform is not Linux, the only platform that refuses to run a file that a process
	 *                       holds open for writing
	 */
	private static Path linuxHarness(TemporaryDirectories cleanup) throws IOException
	{
		if (!Platform.current().isLinux())
			throw new SkipException("Only Linux refuses to run a file that a process holds open for writing");
		Path programs = cleanup.add(Files.createTempDirectory("version-programs-")).toAbsolutePath();
		Path harness = Files.writeString(programs.resolve("harness"), "#!/bin/sh\necho harness 1.2.3\n");
		requireThat(harness.toFile().setExecutable(true, false), "executable").isTrue();
		return harness;
	}
}
