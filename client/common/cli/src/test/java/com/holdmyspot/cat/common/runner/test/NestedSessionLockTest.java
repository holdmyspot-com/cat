package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.WRITE;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.NestedSessionLock;
import com.holdmyspot.cat.common.secrets.NestedSessionLockSecrets;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.testng.annotations.Test;

/**
 * Tests {@link NestedSessionLock}.
 */
public final class NestedSessionLockTest
{
	/**
	 * Creates the test fixture.
	 */
	public NestedSessionLockTest()
	{
	}

	/**
	 * Verifies that while this JVM holds a nested session's lock, a second attempt reports the nested session active
	 * without opening the lock file, and that the lock can be taken again once it is released. On Linux, closing any
	 * channel on a file releases every lock that the process holds on it, so opening and closing the held file would let
	 * another process take an active nested session.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void heldLockFileIsNotOpenedAgain() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-run-lock-"));
			Path file = Files.createFile(directory.resolve(".lock"));
			List<Path> opened = new CopyOnWriteArrayList<>();
			try (NestedSessionLock held = NestedSessionLock.acquire(fileLocks, file))
			{
				requireThat(held.isHeld(), "held.isHeld()").isTrue();
				try
				{
					NestedSessionLockSecrets.getNestedSessionLockAccess().acquire(fileLocks, file, lockFile ->
					{
						opened.add(lockFile);
						return FileChannel.open(lockFile, WRITE);
					}).close();
					throw new AssertionError("Expected the second acquisition to fail");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").startsWith("Nested session is already active");
				}
				requireThat(opened, "opened").isEmpty();
				requireThat(held.isHeld(), "heldAfterSecondAttempt").isTrue();
			}

			try (NestedSessionLock reacquired = NestedSessionLock.acquire(fileLocks, file))
			{
				requireThat(reacquired.isHeld(), "reacquired.isHeld()").isTrue();
			}
		}
	}
}
