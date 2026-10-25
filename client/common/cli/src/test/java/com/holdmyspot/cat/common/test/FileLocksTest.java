package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests ownership of the process's exclusive-file-lock reservations.
 */
public final class FileLocksTest
{
	/**
	 * Creates the test fixture.
	 */
	public FileLocksTest()
	{
	}

	/**
	 * A shared coordinator refuses another reservation without opening a held file, then permits it after release.
	 * The real channel checks the lock lifetime; the refusing opener makes an unsafe opening observable.
	 *
	 * @throws IOException if the owned directory or lock cannot be used
	 */
	@Test
	public void sharesReservationsUntilRelease() throws IOException
	{
		// Requirement: R-T1
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "shared-locks-"));
			Path file = directory.resolve("owned.lock");
			FileLocks locks = new FileLocks();
			try (ExclusiveFileLock held = locks.tryLock(file, path -> FileChannel.open(path, CREATE, WRITE)).orElseThrow())
			{
				requireThat(locks.tryLock(file, _ ->
				{
					throw new IOException("A held file must stay closed");
				}), "reservation").isEmpty();
				requireThat(held.isValid(), "held").isTrue();
			}
			try (ExclusiveFileLock reacquired = locks.tryLock(file, path -> FileChannel.open(path, CREATE, WRITE)).
				orElseThrow())
			{
				requireThat(reacquired.isValid(), "reacquired").isTrue();
			}
		}
	}

	/**
	 * Independent coordinators share no reservation state. The second opener refuses before opening a channel,
	 * so this controlled check cannot release the first coordinator's actual operating-system lock.
	 *
	 * @throws IOException if the owned directory or first lock cannot be used
	 */
	@Test
	public void ownsReservationsIndependently() throws IOException
	{
		// Requirement: R-T1
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "separate-locks-"));
			Path file = directory.resolve("owned.lock");
			FileLocks first = new FileLocks();
			FileLocks second = new FileLocks();
			try (ExclusiveFileLock held = first.tryLock(file, path -> FileChannel.open(path, CREATE, WRITE)).orElseThrow())
			{
				IOException refused = new IOException("Independent coordinator reached its opener");
				try
				{
					second.tryLock(file, _ ->
					{
						throw refused;
					});
					throw new AssertionError("Independent coordinators must not share reservation state");
				}
				catch (IOException actual)
				{
					requireThat(actual, "refusal").isEqualTo(refused);
				}
				requireThat(held.isValid(), "held").isTrue();
			}
		}
	}
}
