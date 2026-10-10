package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.execution.CampaignLock;
import io.github.cowwoc.cat.common.optimizer.execution.fixture.test.FaultingFileChannel;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Covers the lock that admits one driver per campaign.
 */
public final class CampaignLockTest
{
	/**
	 * Creates the test.
	 */
	public CampaignLockTest()
	{
	}

	/**
	 * Verifies that the lock's directory is created, that a held lock cannot be acquired again, and that a released
	 * lock can.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void oneHolderAtATime() throws IOException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("lock")).resolve("campaigns/campaign-1/lock");
			Optional<CampaignLock> first = CampaignLock.tryAcquire(file);
			requireThat(first, "first").isPresent();
			try (CampaignLock _ = first.get())
			{
				requireThat(CampaignLock.tryAcquire(file), "second").isEmpty();
			}
			Optional<CampaignLock> third = CampaignLock.tryAcquire(file);
			requireThat(third, "third").isPresent();
			third.get().close();
		}
	}

	/**
	 * Verifies that while a driver in this JVM holds the lock, a second attempt reports the campaign busy without
	 * opening the lock file, even through a symbolic link to its directory. On Linux, closing any channel on a file
	 * releases every lock that the process holds on it, so opening and closing the held file would let a driver in
	 * another process take the campaign.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void heldLockFileIsNotOpenedAgain() throws IOException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("lock"));
			Path directory = Files.createDirectories(root.resolve("campaigns/campaign-1"));
			Path alias = Files.createSymbolicLink(root.resolve("alias"), directory);
			List<Path> opened = new CopyOnWriteArrayList<>();
			try (CampaignLock _ = CampaignLock.tryAcquire(directory.resolve("lock")).orElseThrow())
			{
				for (Path file : List.of(directory.resolve("lock"), alias.resolve("lock")))
				{
					Optional<CampaignLock> second = CampaignLock.tryAcquire(file, lockFile ->
					{
						opened.add(lockFile);
						return FileChannel.open(lockFile, CREATE, WRITE);
					});
					requireThat(second, "second").isEmpty();
				}
				requireThat(opened, "opened").isEmpty();
			}
			try (CampaignLock _ = CampaignLock.tryAcquire(alias.resolve("lock")).orElseThrow())
			{
				requireThat(CampaignLock.tryAcquire(directory.resolve("lock")), "whileHeldThroughAlias").isEmpty();
			}
		}
	}

	/**
	 * Verifies that acquisition keeps its primary failure and suppresses a channel-close failure.
	 *
	 * @param primary the locking failure
	 * @param cleanup the channel-close failure
	 * @throws IOException the expected I/O failure
	 */
	@Test(dataProvider = "acquisitionFailures", expectedExceptions = {IOException.class, IllegalStateException.class})
	public void acquisitionKeepsPrimaryFailure(Exception primary, Exception cleanup) throws IOException
	{
		try (TemporaryDirectories directories = new TemporaryDirectories();
			FaultingFileChannel channel = new FaultingFileChannel(primary, null, cleanup))
		{
			Path file = directories.add(Files.createTempDirectory("lock")).resolve("lock");
			try
			{
				CampaignLock.tryAcquire(file, _ -> channel);
			}
			catch (IOException | RuntimeException e)
			{
				requireThat(e, "failure").isEqualTo(primary);
				requireThat(List.of(e.getSuppressed()), "suppressed").isEqualTo(List.of(cleanup));
				requireThat(channel.isOpen(), "channelOpen").isFalse();
				throw e;
			}
		}
	}

	/**
	 * Supplies independent I/O and runtime acquisition and cleanup failures.
	 *
	 * @return the pairs of primary and cleanup failures
	 */
	@DataProvider
	public Object[][] acquisitionFailures()
	{
		return new Object[][]
		{
			{new IOException("locking failed"), new IOException("closing failed")},
			{new IOException("locking failed"), new IllegalStateException("closing failed")},
			{new IllegalStateException("locking failed"), new IOException("closing failed")},
			{new IllegalStateException("locking failed"), new IllegalStateException("closing failed")}
		};
	}

	/**
	 * Verifies that releasing a held lock keeps its failure and suppresses a channel-close failure.
	 *
	 * @throws IOException the expected release failure
	 */
	@Test(expectedExceptions = IOException.class, expectedExceptionsMessageRegExp = "release failed")
	public void releaseKeepsPrimaryFailure() throws IOException
	{
		IOException primary = new IOException("release failed");
		IOException cleanup = new IOException("closing failed");
		try (TemporaryDirectories directories = new TemporaryDirectories();
			FaultingFileChannel channel = new FaultingFileChannel(null, primary, cleanup))
		{
			Path file = directories.add(Files.createTempDirectory("lock")).resolve("lock");
			try (CampaignLock _ = CampaignLock.tryAcquire(file, _ -> channel).orElseThrow())
			{
				// Closing the scope releases the campaign lock before closing its channel.
			}
			catch (IOException e)
			{
				requireThat(e, "failure").isEqualTo(primary);
				requireThat(List.of(e.getSuppressed()), "suppressed").isEqualTo(List.of(cleanup));
				requireThat(channel.isOpen(), "channelOpen").isFalse();
				throw e;
			}
		}
	}
}
