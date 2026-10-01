package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many harness sessions run at once, across every CAT process that shares one lock directory.
 * <p>
 * Each permit is an exclusive lock on one of {@code maximum} slot files, so the operating system releases a permit when
 * its process dies. Instances over the same directory draw from the same slots, even inside one JVM.
 */
public final class HarnessCapacity
{
	/**
	 * The highest number of harness sessions that CAT may run at once.
	 */
	public static final int MAXIMUM_CONCURRENCY = 16;
	private static final Duration RETRY_INTERVAL = Duration.ofMillis(100);
	private static final Permit UNLIMITED_PERMIT = () ->
	{
	};
	private final Path lockDirectory;
	private final int maximum;
	private final Pause pause;

	/**
	 * Creates a capacity pool backed by lock files.
	 *
	 * @param lockDirectory the directory that holds the slot files, or {@code null} for an unlimited pool
	 * @param maximum the number of slots
	 * @param pause the wait between attempts to acquire a slot
	 */
	private HarnessCapacity(Path lockDirectory, int maximum, Pause pause)
	{
		this.lockDirectory = lockDirectory;
		this.maximum = maximum;
		this.pause = pause;
	}

	/**
	 * Creates a capacity pool whose slots are files in a directory.
	 *
	 * @param lockDirectory the directory that holds the slot files. It is created on first use.
	 * @param maximum the number of permits that may be held at once, from 1 to {@link #MAXIMUM_CONCURRENCY}
	 * @param pause the wait that {@link #acquire()} performs between attempts
	 * @return the pool
	 * @throws NullPointerException if {@code lockDirectory} or {@code pause} are null
	 * @throws IllegalArgumentException if {@code maximum} is out of range
	 */
	public static HarnessCapacity of(Path lockDirectory, int maximum, Pause pause)
	{
		requireThat(lockDirectory, "lockDirectory").isNotNull();
		requireThat(maximum, "maximum").isBetween(1, MAXIMUM_CONCURRENCY + 1);
		requireThat(pause, "pause").isNotNull();
		return new HarnessCapacity(lockDirectory.toAbsolutePath().normalize(), maximum, pause);
	}

	/**
	 * Creates a pool that grants every request immediately and uses no files.
	 *
	 * @return the pool
	 */
	public static HarnessCapacity unlimited()
	{
		return new HarnessCapacity(null, MAXIMUM_CONCURRENCY, () ->
		{
			throw new AssertionError("An unlimited pool never waits");
		});
	}

	/**
	 * Creates the capacity pool that every CAT process shares for one harness.
	 * <p>
	 * The slot files live in {@code .cat/locks/harness-capacity/<harness>} below {@code home}, so sessions of
	 * different harnesses do not compete for permits. {@link #acquire()} retries every 100 milliseconds.
	 * Only an application entry point should call this method, because it is the one place that resolves the user's
	 * home directory.
	 *
	 * @param home the user's home directory
	 * @param harness the harness name, such as {@code claude}
	 * @param maximum the number of permits that may be held at once, from 1 to {@link #MAXIMUM_CONCURRENCY}
	 * @return the pool
	 * @throws NullPointerException if {@code home} or {@code harness} are null
	 * @throws IllegalArgumentException if {@code harness} is blank or {@code maximum} is out of range
	 */
	public static HarnessCapacity forHome(Path home, String harness, int maximum)
	{
		requireThat(home, "home").isNotNull();
		requireThat(harness, "harness").isNotBlank();
		Path lockDirectory = home.resolve(".cat").resolve("locks").resolve("harness-capacity").resolve(harness);
		return of(lockDirectory, maximum, Pause.sleeping(RETRY_INTERVAL));
	}

	/**
	 * Takes a permit, waiting through the pause until one is free.
	 *
	 * @return the permit. Close it to release the slot.
	 * @throws IOException if a slot file cannot be locked or the pause fails
	 */
	public Permit acquire() throws IOException
	{
		while (true)
		{
			Optional<Permit> permit = tryAcquire();
			if (permit.isPresent())
				return permit.get();
			pause.pause();
		}
	}

	/**
	 * Takes a permit if one is free, without waiting.
	 *
	 * @return the permit, or empty if every slot is held. Close the permit to release the slot.
	 * @throws IOException if a slot file cannot be locked
	 */
	public Optional<Permit> tryAcquire() throws IOException
	{
		if (lockDirectory == null)
			return Optional.of(UNLIMITED_PERMIT);
		Files.createDirectories(lockDirectory);
		for (int slot = 0; slot < maximum; ++slot)
		{
			FileChannel channel = FileChannel.open(lockDirectory.resolve("slot-" + slot + ".lock"), CREATE, WRITE);
			try
			{
				FileLock lock = channel.tryLock();
				if (lock != null)
					return Optional.of(new SlotPermit(channel, lock));
			}
			catch (OverlappingFileLockException _)
			{
				// Another instance in this JVM holds the slot
			}
			catch (IOException e)
			{
				channel.close();
				throw e;
			}
			channel.close();
		}
		return Optional.empty();
	}

	/**
	 * Holds one unit of capacity until it is closed.
	 */
	@FunctionalInterface
	public interface Permit extends AutoCloseable
	{
		/**
		 * Releases the capacity. Closing a permit again has no effect.
		 *
		 * @throws IOException if the slot cannot be released
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * A permit that holds a lock on one slot file.
	 */
	private static final class SlotPermit implements Permit
	{
		private final FileChannel channel;
		private final FileLock lock;
		private final AtomicBoolean closed = new AtomicBoolean();

		/**
		 * Creates a permit for a locked slot.
		 *
		 * @param channel the open slot file
		 * @param lock the lock held on {@code channel}
		 */
		private SlotPermit(FileChannel channel, FileLock lock)
		{
			this.channel = channel;
			this.lock = lock;
		}

		@Override
		public void close() throws IOException
		{
			if (!closed.compareAndSet(false, true))
				return;
			try (channel)
			{
				lock.release();
			}
		}
	}
}
