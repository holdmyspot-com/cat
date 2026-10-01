package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
