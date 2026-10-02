package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.execution.fixture.FaultingFileChannel;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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
	 * Verifies that acquisition keeps its primary failure and suppresses a channel-close failure.
	 *
	 * @param primary the locking failure
	 * @param cleanup the channel-close failure
	 * @throws IOException the expected I/O failure
	 */
	@Test(dataProvider = "acquisitionFailures", expectedExceptions = {IOException.class, IllegalStateException.class})
	public void acquisitionKeepsPrimaryFailure(Exception primary, Exception cleanup) throws IOException
	{
		try (FaultingFileChannel channel = new FaultingFileChannel(primary, null, cleanup))
		{
			try
			{
				CampaignLock.tryAcquire(channel);
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
		try (FaultingFileChannel channel = new FaultingFileChannel(null, primary, cleanup))
		{
			try (CampaignLock _ = CampaignLock.tryAcquire(channel).orElseThrow())
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
