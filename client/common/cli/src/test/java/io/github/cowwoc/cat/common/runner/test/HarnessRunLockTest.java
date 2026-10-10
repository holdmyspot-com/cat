package io.github.cowwoc.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.HarnessRunLock;
import io.github.cowwoc.cat.common.secrets.HarnessRunLockSecrets;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessRunLock}.
 */
public final class HarnessRunLockTest
{
	/**
	 * Creates the test fixture.
	 */
	public HarnessRunLockTest()
	{
	}

	/**
	 * Verifies that while this JVM holds a run's lock, a second attempt reports the run active without opening the
	 * lock file, and that the lock can be taken again once it is released. On Linux, closing any channel on a file
	 * releases every lock that the process holds on it, so opening and closing the held file would let another
	 * process take an active run.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void heldLockFileIsNotOpenedAgain() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-run-lock-"));
			Path file = Files.createFile(directory.resolve(".lock"));
			List<Path> opened = new CopyOnWriteArrayList<>();
			try (HarnessRunLock held = HarnessRunLock.acquire(file))
			{
				requireThat(held.isHeld(), "held.isHeld()").isTrue();
				try
				{
					HarnessRunLockSecrets.access().acquire(file, lockFile ->
					{
						opened.add(lockFile);
						return FileChannel.open(lockFile, WRITE);
					}).close();
					throw new AssertionError("Expected the second acquisition to fail");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").startsWith("Run is already active");
				}
				requireThat(opened, "opened").isEmpty();
				requireThat(held.isHeld(), "heldAfterSecondAttempt").isTrue();
			}

			try (HarnessRunLock reacquired = HarnessRunLock.acquire(file))
			{
				requireThat(reacquired.isHeld(), "reacquired.isHeld()").isTrue();
			}
		}
	}
}
