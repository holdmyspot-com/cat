package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.execution.CampaignLock;
import io.github.cowwoc.cat.common.optimizer.execution.LockHolder;
import io.github.cowwoc.cat.common.optimizer.execution.fixture.test.FaultingFileChannel;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.databind.json.JsonMapper;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Covers the lock that admits one driver per campaign.
 */
public final class CampaignLockTest
{
	private static final String SESSION = "session-1";
	private static final JsonMapper JSON = JsonMapper.builder().build();
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
	 * Verifies that while a driver of one host session holds the lock, a driver of another session reports the
	 * campaign busy without waiting.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test(timeOut = 10_000)
	public void otherSessionIsBusy() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("lock")).resolve("campaigns/campaign-1/lock");
			try (CampaignLock _ = CampaignLock.acquire(file, LockHolder.current(SESSION), JSON).orElseThrow())
			{
				requireThat(CampaignLock.acquire(file, LockHolder.current("session-2"), JSON), "second").isEmpty();
			}
		}
	}

	/**
	 * Verifies that while a driver of the same host session holds the lock, and its process did not start the
	 * caller's, a second driver waits for the lock and takes it once the holder releases it. A host session runs
	 * one command after another, so its earlier command ends unless the host lost track of it.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 * @throws ExecutionException if the waiting driver fails
	 * @throws TimeoutException if the waiting driver does not take the lock
	 */
	@Test(timeOut = 10_000)
	public void sameSessionWaitsForHolder()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("lock")).resolve("campaigns/campaign-1/lock");
			// The process identifier is reused by a process that started at another time, so it is not an ancestor
			LockHolder earlierCommand = new LockHolder(SESSION, ProcessHandle.current().pid(), Instant.EPOCH);
			CompletableFuture<Optional<CampaignLock>> second = new CompletableFuture<>();
			try (CampaignLock _ = CampaignLock.acquire(file, earlierCommand, JSON).orElseThrow())
			{
				Thread waiter = Thread.ofPlatform().start(() ->
				{
					try
					{
						second.complete(CampaignLock.acquire(file, LockHolder.current(SESSION), JSON));
					}
					catch (IOException | InterruptedException | RuntimeException e)
					{
						second.completeExceptionally(e);
					}
				});
				while (waiter.getState() != Thread.State.WAITING && !second.isDone())
					Thread.onSpinWait();
				requireThat(second.isDone(), "doneWhileHeld").isFalse();
			}

			Optional<CampaignLock> acquired = second.get(5, TimeUnit.SECONDS);
			requireThat(acquired, "acquired").isPresent();
			acquired.get().close();
		}
	}

	/**
	 * Verifies that a driver fails at once, instead of waiting, when the lock's holder is its own process or one that
	 * started it. That holder ends only after the driver does, so waiting for it would never end.
	 *
	 * @param holder returns the holder's process
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test(dataProvider = "ancestors", timeOut = 10_000, expectedExceptions = IllegalStateException.class)
	public void ancestorHolderFailsAtOnce(ProcessHandle holder) throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path file = cleanup.add(Files.createTempDirectory("lock")).resolve("campaigns/campaign-1/lock");
			LockHolder ancestor = new LockHolder(SESSION, holder.pid(), holder.info().startInstant().orElse(null));
			try (CampaignLock _ = CampaignLock.acquire(file, ancestor, JSON).orElseThrow())
			{
				CampaignLock.acquire(file, LockHolder.current(SESSION), JSON);
			}
		}
	}

	/**
	 * Supplies the current process and the process that started it.
	 *
	 * @return the processes
	 */
	@DataProvider
	public Object[][] ancestors()
	{
		ProcessHandle current = ProcessHandle.current();
		return new Object[][]
		{
			{current},
			{current.parent().orElseThrow()}
		};
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
