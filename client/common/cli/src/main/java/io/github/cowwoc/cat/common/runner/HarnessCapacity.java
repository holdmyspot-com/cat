package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many harness sessions run at once, across every CAT process that shares one lock directory, and holds
 * every new session of the harness after the provider rejects or refuses a request.
 * <p>
 * Each permit is an exclusive lock on one of {@code maximum} slot files, so the operating system releases a permit when
 * its process dies. Instances over the same directory draw from the same slots, even inside one JVM.
 * <p>
 * The hold lives in {@code hold.json} beside the slot files, so every instance over the directory honours it: no
 * permit is granted until the hold ends. A rate-limit rejection with a future reset time holds until that time. A
 * rejection without one, or a refusal, holds for the next hold length: 1 minute, doubling with each rejection or
 * refusal that follows the end of a hold, up to 30 minutes. A rejection or refusal while a hold lasts changes neither
 * the hold nor the next length, and a turn that completes resets the next length to 1 minute. Updates are atomic and
 * serialized by {@code hold.lock}, and no update moves the end of a hold earlier. An unlimited pool has no hold.
 */
public final class HarnessCapacity
{
	/**
	 * The highest number of harness sessions that CAT may run at once.
	 */
	public static final int MAXIMUM_CONCURRENCY = 16;
	private static final Duration RETRY_INTERVAL = Duration.ofMillis(100);
	/**
	 * The longest hold.
	 */
	private static final Duration LONGEST_HOLD = Duration.ofMinutes(30);
	/**
	 * The number of doubled holds after which every hold lasts {@link #LONGEST_HOLD}.
	 */
	private static final int LONGEST_BACKOFFS = 6;
	private static final String HOLD_FILE = "hold.json";
	private static final String HOLD_LOCK = "hold.lock";
	private static final int HOLD_FORMAT_VERSION = 1;
	private static final Set<String> HOLD_FIELDS = Set.of("format_version", "ends_at", "backoffs");
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * Serializes hold updates inside this JVM, where a second lock on {@code hold.lock} would fail instead of waiting.
	 */
	private static final ConcurrentMap<Path, Object> HOLD_MONITORS = new ConcurrentHashMap<>();
	private static final Permit UNLIMITED_PERMIT = () ->
	{
	};
	private final Path lockDirectory;
	private final int maximum;
	private final Pause pause;
	private final Clock clock;

	/**
	 * Creates a capacity pool backed by lock files.
	 *
	 * @param lockDirectory the directory that holds the slot files, or {@code null} for an unlimited pool
	 * @param maximum the number of slots
	 * @param pause the wait between attempts to acquire a slot
	 * @param clock the clock that decides whether a hold lasts
	 */
	private HarnessCapacity(Path lockDirectory, int maximum, Pause pause, Clock clock)
	{
		this.lockDirectory = lockDirectory;
		this.maximum = maximum;
		this.pause = pause;
		this.clock = clock;
	}

	/**
	 * Creates a capacity pool whose slots are files in a directory.
	 *
	 * @param lockDirectory the directory that holds the slot files. It is created on first use.
	 * @param maximum the number of permits that may be held at once, from 1 to {@link #MAXIMUM_CONCURRENCY}
	 * @param pause the wait that {@link #acquire()} performs between attempts
	 * @param clock the clock that decides whether a hold lasts
	 * @return the pool
	 * @throws NullPointerException if {@code lockDirectory}, {@code pause} or {@code clock} are null
	 * @throws IllegalArgumentException if {@code maximum} is out of range
	 */
	public static HarnessCapacity of(Path lockDirectory, int maximum, Pause pause, Clock clock)
	{
		requireThat(lockDirectory, "lockDirectory").isNotNull();
		requireThat(maximum, "maximum").isBetween(1, MAXIMUM_CONCURRENCY + 1);
		requireThat(pause, "pause").isNotNull();
		requireThat(clock, "clock").isNotNull();
		return new HarnessCapacity(lockDirectory.toAbsolutePath().normalize(), maximum, pause, clock);
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
		}, Clock.systemUTC());
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
		return of(lockDirectory, maximum, Pause.sleeping(RETRY_INTERVAL), Clock.systemUTC());
	}

	/**
	 * Takes a permit, waiting through the pause until no hold lasts and a slot is free.
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
	 * Takes a permit if one is free and no hold lasts, without waiting.
	 *
	 * @return the permit, or empty if a hold lasts or every slot is held. Close the permit to release the slot.
	 * @throws IOException if the hold file cannot be read or is malformed, or a slot file cannot be locked
	 */
	public Optional<Permit> tryAcquire() throws IOException
	{
		if (lockDirectory == null)
			return Optional.of(UNLIMITED_PERMIT);
		if (clock.instant().isBefore(readHold().endsAt()))
			return Optional.empty();
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
	 * Returns the harness's hold, which may already have ended.
	 *
	 * @return the hold, or {@link Hold#NONE} if none was recorded or the pool is unlimited
	 * @throws IOException if the hold file cannot be read or is malformed
	 */
	public Hold hold() throws IOException
	{
		if (lockDirectory == null)
			return Hold.NONE;
		return readHold();
	}

	/**
	 * Records that the provider rejected a request because of a rate limit.
	 *
	 * @param resetsAt when the provider accepts requests again, or {@code null} if it did not say
	 * @return the hold after the rejection, or {@link Hold#NONE} if the pool is unlimited
	 * @throws IOException if the hold file cannot be read, locked or written, or is malformed
	 */
	public Hold reject(Instant resetsAt) throws IOException
	{
		if (lockDirectory == null)
			return Hold.NONE;
		synchronized (holdMonitor())
		{
			try (FileChannel channel = FileChannel.open(lockDirectory.resolve(HOLD_LOCK), CREATE, WRITE);
				FileLock _ = channel.lock())
			{
				Hold current = readHold();
				Instant now = clock.instant();
				Hold next;
				if (resetsAt != null && resetsAt.isAfter(now))
				{
					if (!resetsAt.isAfter(current.endsAt()))
						return current;
					next = new Hold(resetsAt, current.backoffs());
				}
				else
					next = current.extendedAt(now);
				if (!next.equals(current))
					writeHold(next);
				return next;
			}
		}
	}

	/**
	 * Records that the provider refused a request with HTTP status 403.
	 *
	 * @return the hold after the refusal, and whether the refusal followed the end of a 30-minute hold. An unlimited
	 *   pool returns {@link Hold#NONE} and {@code false}.
	 * @throws IOException if the hold file cannot be read, locked or written, or is malformed
	 */
	public Refusal refuse() throws IOException
	{
		if (lockDirectory == null)
			return new Refusal(Hold.NONE, false);
		synchronized (holdMonitor())
		{
			try (FileChannel channel = FileChannel.open(lockDirectory.resolve(HOLD_LOCK), CREATE, WRITE);
				FileLock _ = channel.lock())
			{
				Hold current = readHold();
				Instant now = clock.instant();
				boolean afterLongestHold = !now.isBefore(current.endsAt()) && current.backoffs() >= LONGEST_BACKOFFS;
				Hold next = current.extendedAt(now);
				if (!next.equals(current))
					writeHold(next);
				return new Refusal(next, afterLongestHold);
			}
		}
	}

	/**
	 * Records that a turn completed, which resets the next hold length to 1 minute without ending a hold that lasts.
	 *
	 * @throws IOException if the hold file cannot be read, locked or written, or is malformed
	 */
	public void completeTurn() throws IOException
	{
		if (lockDirectory == null || readHold().backoffs() == 0)
			return;
		synchronized (holdMonitor())
		{
			try (FileChannel channel = FileChannel.open(lockDirectory.resolve(HOLD_LOCK), CREATE, WRITE);
				FileLock _ = channel.lock())
			{
				Hold current = readHold();
				if (current.backoffs() != 0)
					writeHold(new Hold(current.endsAt(), 0));
			}
		}
	}

	/**
	 * Returns the object that serializes this directory's hold updates inside this JVM, creating the directory.
	 *
	 * @return the monitor
	 * @throws IOException if the directory cannot be created
	 */
	private Object holdMonitor() throws IOException
	{
		Files.createDirectories(lockDirectory);
		return HOLD_MONITORS.computeIfAbsent(lockDirectory, _ -> new Object());
	}

	/**
	 * Reads the hold file.
	 *
	 * @return the recorded hold, or {@link Hold#NONE} if the file does not exist
	 * @throws IOException if the file cannot be read or does not match its format
	 */
	private Hold readHold() throws IOException
	{
		Path file = lockDirectory.resolve(HOLD_FILE);
		// The file holds one small object, so it is read whole
		byte[] content;
		try
		{
			content = Files.readAllBytes(file);
		}
		catch (NoSuchFileException _)
		{
			return Hold.NONE;
		}
		JsonNode root;
		try
		{
			root = JSON.readTree(content);
		}
		catch (JacksonException e)
		{
			throw malformedHold(file, "it is not JSON", e);
		}
		if (root == null || !root.isObject())
			throw malformedHold(file, "it is not a JSON object", null);
		Set<String> unknown = new HashSet<>(root.propertyNames());
		unknown.removeAll(HOLD_FIELDS);
		if (!unknown.isEmpty())
			throw malformedHold(file, "it has unknown fields " + unknown, null);
		JsonNode version = root.path("format_version");
		if (!version.isInt() || version.asInt() != HOLD_FORMAT_VERSION)
			throw malformedHold(file, "format_version is " + version + " instead of " + HOLD_FORMAT_VERSION, null);
		JsonNode endsAt = root.path("ends_at");
		if (!endsAt.isString())
			throw malformedHold(file, "ends_at is not an ISO-8601 instant", null);
		Instant end;
		try
		{
			end = Instant.parse(endsAt.asString());
		}
		catch (DateTimeParseException e)
		{
			throw malformedHold(file, "ends_at is not an ISO-8601 instant", e);
		}
		JsonNode backoffs = root.path("backoffs");
		if (!backoffs.isInt() || backoffs.asInt() < 0 || backoffs.asInt() > LONGEST_BACKOFFS)
			throw malformedHold(file, "backoffs is not an integer from 0 to " + LONGEST_BACKOFFS, null);
		return new Hold(end, backoffs.asInt());
	}

	/**
	 * Returns the exception that reports a malformed hold file.
	 *
	 * @param file the hold file
	 * @param problem how the file departs from its format
	 * @param cause the parsing failure, or {@code null}
	 * @return the exception
	 */
	private static IOException malformedHold(Path file, String problem, Exception cause)
	{
		return new IOException("The harness hold file " + file + " is malformed: " + problem + ". Delete the file to " +
			"clear the hold.", cause);
	}

	/**
	 * Replaces the hold file atomically. The caller holds the hold lock.
	 *
	 * @param hold the hold to record
	 * @throws IOException if the file cannot be written
	 */
	private void writeHold(Hold hold) throws IOException
	{
		ObjectNode root = JSON.createObjectNode();
		root.put("format_version", HOLD_FORMAT_VERSION);
		root.put("ends_at", hold.endsAt().toString());
		root.put("backoffs", hold.backoffs());
		Path file = lockDirectory.resolve(HOLD_FILE);
		Path temporary = lockDirectory.resolve(HOLD_FILE + ".tmp");
		try
		{
			Files.write(temporary, JSON.writeValueAsBytes(root));
			Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			Files.deleteIfExists(temporary);
			throw e;
		}
	}

	/**
	 * A hold on a harness's new sessions.
	 *
	 * @param endsAt when the hold ends; it lasts while the current time is before this instant
	 * @param backoffs the number of doubled holds since the last completed turn, from 0 to 6. The next hold without
	 *   a reset time lasts 2<sup>{@code backoffs}</sup> minutes, up to 30 minutes.
	 */
	public record Hold(Instant endsAt, int backoffs)
	{
		/**
		 * No hold.
		 */
		public static final Hold NONE = new Hold(Instant.EPOCH, 0);

		/**
		 * Creates a hold.
		 *
		 * @param endsAt when the hold ends; it lasts while the current time is before this instant
		 * @param backoffs the number of doubled holds since the last completed turn, from 0 to 6
		 * @throws NullPointerException if {@code endsAt} is null
		 * @throws IllegalArgumentException if {@code backoffs} is out of range
		 */
		public Hold
		{
			requireThat(endsAt, "endsAt").isNotNull();
			requireThat(backoffs, "backoffs").isBetween(0, true, LONGEST_BACKOFFS, true);
		}

		/**
		 * Returns the hold after a rejection without a reset time, or a refusal.
		 *
		 * @param now the current time
		 * @return this hold if it lasts at {@code now}; otherwise a hold that starts at {@code now} and lasts the next
		 *   hold length
		 */
		private Hold extendedAt(Instant now)
		{
			if (now.isBefore(endsAt))
				return this;
			Duration length = Duration.ofMinutes(1L << backoffs);
			if (length.compareTo(LONGEST_HOLD) > 0)
				length = LONGEST_HOLD;
			return new Hold(now.plus(length), Math.min(backoffs + 1, LONGEST_BACKOFFS));
		}
	}

	/**
	 * The outcome of a refusal.
	 *
	 * @param hold the hold after the refusal
	 * @param afterLongestHold {@code true} if the refusal followed the end of a 30-minute hold
	 */
	public record Refusal(Hold hold, boolean afterLongestHold)
	{
		/**
		 * Creates the outcome of a refusal.
		 *
		 * @param hold the hold after the refusal
		 * @param afterLongestHold {@code true} if the refusal followed the end of a 30-minute hold
		 * @throws NullPointerException if {@code hold} is null
		 */
		public Refusal
		{
			requireThat(hold, "hold").isNotNull();
		}
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
