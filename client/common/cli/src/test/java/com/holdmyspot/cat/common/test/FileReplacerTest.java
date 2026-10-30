package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.Test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Verifies that {@link FileReplacer} retries a refused replacement on Windows, through its pause and until its clock
 * reaches the limit, and fails at once elsewhere.
 */
public final class FileReplacerTest
{
	private static final Set<PosixFilePermission> READ_ONLY_DIRECTORY =
		Set.copyOf(PosixFilePermissions.fromString("r-xr-xr-x"));
	private static final Set<PosixFilePermission> WRITABLE_DIRECTORY =
		Set.copyOf(PosixFilePermissions.fromString("rwxr-xr-x"));

	/**
	 * Creates the test fixture.
	 */
	public FileReplacerTest()
	{
	}

	/**
	 * Verifies that on Windows a refused replacement pauses and tries again, and succeeds once the refusal ends.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void retriesRefusalOnWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("file-replacer-"));
			Path target = Files.writeString(directory.resolve("run.json"), "old");
			Path source = Files.writeString(directory.resolve("run.json.tmp"), "new");
			AtomicInteger pauses = new AtomicInteger();

			try (Refusal refusal = new Refusal(directory, target))
			{
				FileReplacer replacer = new FileReplacer(Platform.WINDOWS_X64, () ->
				{
					pauses.incrementAndGet();
					refusal.end();
				}, new ManualClock(Instant.EPOCH));
				replacer.replace(source, target);
			}

			requireThat(pauses.get(), "pauses").isEqualTo(1);
			requireThat(Files.readString(target), "content").isEqualTo("new");
			requireThat(Files.exists(source), "sourceExists").isFalse();
		}
	}

	/**
	 * Verifies that on Windows a refusal that outlasts the five-second limit fails after the pauses that fit in it.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void givesUpAtRetryLimit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("file-replacer-"));
			Path target = Files.writeString(directory.resolve("run.json"), "old");
			Path source = Files.writeString(directory.resolve("run.json.tmp"), "new");
			ManualClock clock = new ManualClock(Instant.EPOCH);
			AtomicInteger pauses = new AtomicInteger();
			FileReplacer replacer = new FileReplacer(Platform.WINDOWS_X64, () ->
			{
				pauses.incrementAndGet();
				clock.advance(Duration.ofSeconds(1));
			}, clock);

			try (Refusal _ = new Refusal(directory, target))
			{
				replacer.replace(source, target);
				throw new AssertionError("The replacement succeeded while the file system refused it");
			}
			catch (AccessDeniedException _)
			{
			}

			requireThat(pauses.get(), "pauses").isEqualTo(5);
			requireThat(Files.readString(target), "content").isEqualTo("old");
		}
	}

	/**
	 * Verifies that a failed pause ends the replacement, keeping the refusal as a suppressed exception.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void pauseFailureEndsReplacement() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("file-replacer-"));
			Path target = Files.writeString(directory.resolve("run.json"), "old");
			Path source = Files.writeString(directory.resolve("run.json.tmp"), "new");
			IOException interrupted = new IOException("interrupted");
			FileReplacer replacer = new FileReplacer(Platform.WINDOWS_X64, () ->
			{
				throw interrupted;
			}, new ManualClock(Instant.EPOCH));

			try (Refusal _ = new Refusal(directory, target))
			{
				replacer.replace(source, target);
				throw new AssertionError("The replacement succeeded while the file system refused it");
			}
			catch (IOException failure)
			{
				requireThat(failure, "failure").isReferenceEqualTo(interrupted, "interrupted");
				requireThat(failure.getSuppressed()[0], "suppressed").isInstanceOf(AccessDeniedException.class);
			}
		}
	}

	/**
	 * Verifies that outside Windows a refused replacement fails without pausing.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failsAtOnceOutsideWindows() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("file-replacer-"));
			Path target = Files.writeString(directory.resolve("run.json"), "old");
			Path source = Files.writeString(directory.resolve("run.json.tmp"), "new");
			FileReplacer replacer = new FileReplacer(Platform.LINUX_X64, () ->
			{
				throw new AssertionError("A refusal outside Windows must not pause");
			}, new ManualClock(Instant.EPOCH));

			try (Refusal _ = new Refusal(directory, target))
			{
				replacer.replace(source, target);
				throw new AssertionError("The replacement succeeded while the file system refused it");
			}
			catch (AccessDeniedException _)
			{
			}

			requireThat(Files.readString(target), "content").isEqualTo("old");
		}
	}

	/**
	 * Makes the file system refuse to replace a file until the refusal ends. On Windows a reader holds the file
	 * open; elsewhere the file's directory is read-only.
	 */
	private static final class Refusal implements Closeable
	{
		private final Path directory;
		private final InputStream reader;
		private boolean closed;

		/**
		 * Starts refusing replacements of a file.
		 *
		 * @param directory the file's directory
		 * @param target    the file
		 * @throws IOException if the file cannot be opened or the directory's permissions cannot be changed
		 */
		Refusal(Path directory, Path target) throws IOException
		{
			this.directory = directory;
			if (Platform.current().isWindows())
				reader = Files.newInputStream(target);
			else
			{
				reader = null;
				Files.setPosixFilePermissions(directory, READ_ONLY_DIRECTORY);
			}
		}

		/**
		 * Ends the refusal. Ending it again does nothing.
		 *
		 * @throws IOException if the file cannot be closed or the directory's permissions cannot be restored
		 */
		void end() throws IOException
		{
			if (closed)
				return;
			closed = true;
			if (reader == null)
				Files.setPosixFilePermissions(directory, WRITABLE_DIRECTORY);
			else
				reader.close();
		}

		@Override
		public void close() throws IOException
		{
			end();
		}
	}
}
