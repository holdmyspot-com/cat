package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.LockFileOpener;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessCapacityWaitExpiredException;
import com.holdmyspot.cat.common.secrets.HarnessCapacitySecrets;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Tests {@link HarnessCapacity}.
 */
public final class HarnessCapacityTest
{
	/**
	 * The clock of tests that do not depend on holds.
	 */
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
	/**
	 * The time at which tests of holds start.
	 */
	private static final Instant HOLD_START = Instant.parse("2026-10-06T12:00:00Z");

	/**
	 * Creates the test fixture.
	 */
	public HarnessCapacityTest()
	{
	}

	/**
	 * Returns a pause that fails the test if capacity ever waits.
	 *
	 * @return a pause that must not be called
	 */
	private static Pause unexpectedPause()
	{
		return () ->
		{
			throw new AssertionError("Capacity must not wait");
		};
	}

	/**
	 * Verifies that permits are granted until the maximum is reached.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void acquiresUpToMaximum() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity capacity = HarnessCapacity.of(fileLocks, directory, 3, unexpectedPause(), CLOCK);

			try (HarnessCapacity.Permit first = capacity.acquire();
				HarnessCapacity.Permit second = capacity.acquire();
				HarnessCapacity.Permit third = capacity.acquire())
			{
				requireThat(first, "first").isNotNull();
				requireThat(second, "second").isNotNull();
				requireThat(third, "third").isNotNull();
			}
		}
	}

	/**
	 * Verifies that {@code tryAcquire} fails without waiting once the maximum is reached.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void tryAcquireFailsAtMaximum() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity capacity = HarnessCapacity.of(fileLocks, directory, 2, unexpectedPause(), CLOCK);

			Optional<HarnessCapacity.Permit> first = capacity.tryAcquire();
			Optional<HarnessCapacity.Permit> second = capacity.tryAcquire();
			Optional<HarnessCapacity.Permit> third = capacity.tryAcquire();

			requireThat(first.isPresent(), "first.isPresent").isTrue();
			requireThat(second.isPresent(), "second.isPresent").isTrue();
			requireThat(third.isPresent(), "third.isPresent").isFalse();
			first.orElseThrow().close();
			second.orElseThrow().close();
		}
	}

	/**
	 * Verifies that closing a permit makes its slot available again.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void releasesSlotWhenPermitCloses() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity capacity = HarnessCapacity.of(fileLocks, directory, 1, unexpectedPause(), CLOCK);
			try (HarnessCapacity.Permit held = capacity.tryAcquire().orElseThrow())
			{
				requireThat(held, "held").isNotNull();
				requireThat(capacity.tryAcquire().isPresent(), "acquiredWhileHeld").isFalse();
			}

			try (HarnessCapacity.Permit reacquired = capacity.tryAcquire().orElseThrow())
			{
				requireThat(reacquired, "reacquired").isNotNull();
			}
		}
	}

	/**
	 * Verifies that closing a permit twice does not release a slot that another permit has since taken.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void closingPermitTwiceIsHarmless() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity capacity = HarnessCapacity.of(fileLocks, directory, 1, unexpectedPause(), CLOCK);
			HarnessCapacity.Permit first = capacity.tryAcquire().orElseThrow();
			try (first)
			{
				requireThat(capacity.tryAcquire().isPresent(), "acquiredWhileFirstHeld").isFalse();
			}
			try (HarnessCapacity.Permit second = capacity.tryAcquire().orElseThrow())
			{
				first.close();

				requireThat(second, "second").isNotNull();
				requireThat(capacity.tryAcquire().isPresent(), "acquiredWhileSecondHeld").isFalse();
			}
		}
	}

	/**
	 * Verifies that instances created over one directory draw from the same slots.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void sharesSlotsAcrossInstances() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity first = HarnessCapacity.of(fileLocks, directory, 1, unexpectedPause(), CLOCK);
			HarnessCapacity second = HarnessCapacity.of(fileLocks, directory, 1, unexpectedPause(), CLOCK);
			try (HarnessCapacity.Permit held = first.tryAcquire().orElseThrow())
			{
				requireThat(held, "held").isNotNull();
				requireThat(second.tryAcquire().isPresent(), "acquiredWhileHeld").isFalse();
			}

			try (HarnessCapacity.Permit reacquired = second.tryAcquire().orElseThrow())
			{
				requireThat(reacquired, "reacquired").isNotNull();
			}
		}
	}

	/**
	 * Verifies that instances over different directories do not compete.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void doesNotShareSlotsBetweenDirectories() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity first = HarnessCapacity.of(fileLocks, root.resolve("first"), 1, unexpectedPause(), CLOCK);
			HarnessCapacity second = HarnessCapacity.of(fileLocks, root.resolve("second"), 1, unexpectedPause(), CLOCK);

			try (HarnessCapacity.Permit permit = first.acquire())
			{
				requireThat(permit, "permit").isNotNull();
				try (HarnessCapacity.Permit other = second.acquire())
				{
					requireThat(other, "other").isNotNull();
				}
			}
		}
	}

	/**
	 * Verifies that {@code acquire} waits through the injected pause until a slot is released.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void acquireWaitsThroughPauseUntilReleased() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			AtomicInteger pauses = new AtomicInteger();
			AtomicReference<HarnessCapacity.Permit> held = new AtomicReference<>();
			Pause releaseOnThirdPause = () ->
			{
				if (pauses.incrementAndGet() == 3)
					held.get().close();
			};
			HarnessCapacity capacity = HarnessCapacity.of(fileLocks, directory, 1, releaseOnThirdPause, CLOCK);
			held.set(capacity.acquire());

			try (HarnessCapacity.Permit permit = capacity.acquire())
			{
				requireThat(permit, "permit").isNotNull();
				requireThat(pauses.get(), "pauses").isEqualTo(3);
			}
		}
	}

	/**
	 * Verifies that a failing pause aborts {@code acquire} with the pause's failure.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void acquirePropagatesPauseFailure() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			Pause failing = () ->
			{
				throw new IOException("interrupted");
			};
			HarnessCapacity capacity = HarnessCapacity.of(fileLocks, directory, 1, failing, CLOCK);
			try (HarnessCapacity.Permit held = capacity.acquire())
			{
				requireThat(held, "held").isNotNull();
				try
				{
					capacity.acquire();
					throw new AssertionError("Expected acquire to fail");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").isEqualTo("interrupted");
				}
			}
		}
	}

	/**
	 * Verifies that a bounded wait gives up with a dedicated failure once the wait has passed without a free slot, and
	 * that it returns a permit when a slot frees in time.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void boundedAcquireGivesUp() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			AtomicInteger pauses = new AtomicInteger();
			Pause minute = () ->
			{
				if (pauses.incrementAndGet() > 20)
					throw new AssertionError("The bounded wait did not end");
				clock.advance(Duration.ofMinutes(1));
			};
			HarnessCapacity capacity = HarnessCapacity.of(fileLocks, directory, 1, minute, clock);
			try (HarnessCapacity.Permit _ = capacity.acquire())
			{
				try
				{
					capacity.acquire(Duration.ofMinutes(10));
					throw new AssertionError("Expected the wait to expire");
				}
				catch (HarnessCapacityWaitExpiredException e)
				{
					requireThat(pauses.get(), "pauses").isEqualTo(10);
					requireThat(e.getMessage(), "message").contains("600 seconds");
				}
			}
			try (HarnessCapacity.Permit permit = capacity.acquire(Duration.ofMinutes(10)))
			{
				requireThat(permit, "permit").isNotNull();
			}
		}
	}

	/**
	 * Verifies that an unlimited instance never waits and never touches the filesystem.
	 *
	 * @throws IOException if a permit cannot be closed
	 */
	@Test
	public void unlimitedNeverWaits() throws IOException
	{
		// Requirement: R-E8
		HarnessCapacity capacity = HarnessCapacity.unlimited();

		for (int i = 0; i < HarnessCapacity.MAXIMUM_CONCURRENCY * 4; ++i)
		{
			Optional<HarnessCapacity.Permit> permit = capacity.tryAcquire();
			requireThat(permit.isPresent(), "permit.isPresent").isTrue();
			permit.orElseThrow().close();
		}
		try (HarnessCapacity.Permit permit = capacity.acquire())
		{
			requireThat(permit, "permit").isNotNull();
		}
	}

	/**
	 * Verifies that a maximum above the shared ceiling is rejected.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMaximumAboveCeiling() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity.of(fileLocks, directory, HarnessCapacity.MAXIMUM_CONCURRENCY + 1, unexpectedPause(), CLOCK);
		}
	}

	/**
	 * Verifies that a maximum below one is rejected.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMaximumBelowOne() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity.of(fileLocks, directory, 0, unexpectedPause(), CLOCK);
		}
	}

	/**
	 * Verifies that each harness gets its own lock directory below the user's home, so that harnesses do not compete.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void forHomeSeparatesHarnesses() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-capacity-home-"));
			HarnessCapacity claude = HarnessCapacity.forHome(fileLocks, home, "claude", 1, Yielding.waiting());
			HarnessCapacity codex = HarnessCapacity.forHome(fileLocks, home, "codex", 1, Yielding.waiting());

			try (HarnessCapacity.Permit permit = claude.acquire())
			{
				requireThat(permit, "permit").isNotNull();
				requireThat(Files.exists(home.resolve(".cat/locks/harness-capacity/claude/slot-0.lock")),
					"claudeSlotExists").isTrue();
				try (HarnessCapacity.Permit other = codex.tryAcquire().orElseThrow())
				{
					requireThat(other, "other").isNotNull();
				}
			}
		}
	}

	/**
	 * Verifies that a blocking pause stops waiting with an {@code IOException} when its thread is interrupted, and
	 * keeps the interrupt flag set.
	 */
	@Test
	public void blockingPauseFailsWhenInterrupted()
	{
		// Requirement: R-E8
		Pause pause = Pause.interruptible(() ->
		{
			throw new InterruptedException("interrupted while waiting");
		});
		try
		{
			pause.pause();
			throw new AssertionError("Expected the pause to fail");
		}
		catch (IOException e)
		{
			requireThat(Thread.currentThread().isInterrupted(), "interrupted").isTrue();
			requireThat(e.getMessage(), "message").contains("Interrupted");
		}
		finally
		{
			// Clear the flag so that the test framework thread is reusable
			Thread.interrupted();
		}
	}


	/**
	 * Creates a capacity pool over a test-owned directory whose holds follow a test-controlled clock.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param directory the directory that holds the slot and hold files
	 * @param clock the clock that decides whether a hold lasts
	 * @return the pool
	 */
	private static HarnessCapacity heldBy(FileLocks fileLocks, Path directory, ManualClock clock)
	{
		return HarnessCapacity.of(fileLocks, directory, 1, unexpectedPause(), clock);
	}

	/**
	 * Verifies that a hold that one instance records stops a second instance over the same directory from taking a
	 * permit until the hold ends.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void holdBlocksOtherInstancesUntilItEnds() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			HarnessCapacity first = heldBy(fileLocks, directory, clock);
			HarnessCapacity second = heldBy(fileLocks, directory, clock);

			HarnessCapacity.Hold hold = first.reject(null);

			requireThat(hold, "hold").isEqualTo(new HarnessCapacity.Hold(HOLD_START.plus(Duration.ofMinutes(1)), 1));
			requireThat(second.hold(), "second.hold()").isEqualTo(hold);
			requireThat(second.tryAcquire().isPresent(), "acquiredDuringHold").isFalse();
			clock.set(hold.endsAt().minusSeconds(1));
			requireThat(second.tryAcquire().isPresent(), "acquiredBeforeEnd").isFalse();
			clock.set(hold.endsAt());
			try (HarnessCapacity.Permit permit = second.tryAcquire().orElseThrow())
			{
				requireThat(permit, "permit").isNotNull();
			}
		}
	}

	/**
	 * Verifies that a hold recorded by one JVM prevents a second JVM from acquiring a permit before expiry, and that
	 * the second JVM can acquire at expiry. Two instances in one JVM could share an in-memory hold and miss a failure
	 * to persist it. One child JVM records the hold; the test JVM's existing pool must then honour it. This process
	 * boundary uses a controlled clock, starts no harness or model and does not sleep.
	 * <p>
	 * Starting the child JVM and loading its classes can take one second or longer, especially during a parallel build.
	 *
	 * @throws IOException if the fixture cannot run or its files cannot be used
	 * @throws InterruptedException if waiting for a fixture is interrupted
	 */
	@Test
	public void holdBlocksAnotherJvmUntilExpiry() throws IOException, InterruptedException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E6, R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-process-"));
			ManualClock clock = new ManualClock(HOLD_START);
			HarnessCapacity capacity = heldBy(fileLocks, directory, clock);
			requireThat(capacity.hold(), "initialHold").isEqualTo(HarnessCapacity.Hold.NONE);
			requireThat(capacityProcess(directory), "recordedHold").
				isEqualTo(HOLD_START.plus(Duration.ofMinutes(1)).toString());
			requireThat(capacity.tryAcquire().isPresent(), "admittedDuringHold").isFalse();
			clock.advance(Duration.ofMinutes(1));
			try (HarnessCapacity.Permit permit = capacity.tryAcquire().orElseThrow())
			{
				requireThat(permit, "admittedAtExpiry").isNotNull();
			}
		}
	}

	/**
	 * Records a hold in a separate JVM with the test runtime's classes and dependencies.
	 *
	 * @param directory the isolated lock and process working directory
	 * @return the fixture's output with platform line endings normalized
	 * @throws IOException if the process cannot start or its output cannot be read
	 * @throws InterruptedException if waiting is interrupted
	 */
	private static String capacityProcess(Path directory) throws IOException, InterruptedException
	{
		String executable = "java";
		if (Platform.current().isWindows())
			executable = "java.exe";
		Path java = Path.of(System.getProperty("java.home"), "bin", executable);
		String classes = System.getProperty("jdk.module.path", System.getProperty("java.class.path"));
		ProcessBuilder builder = new ProcessBuilder(java.toString(), "-XX:+PerfDisableSharedMem",
			"-Djava.io.tmpdir=" + directory,
			"-Duser.home=" + directory.resolve("absent-home"), "-cp", classes, CapacityHoldProcess.class.getName(),
			directory.toString(), HOLD_START.toString()).directory(directory.toFile()).redirectErrorStream(true);
		try (Process process = builder.start())
		{
			requireThat(process.waitFor(10, TimeUnit.SECONDS), "finished").isTrue();
			String output = new String(process.getInputStream().readAllBytes(), UTF_8).replace("\r\n", "\n").strip();
			if (process.exitValue() != 0)
				throw new IOException("Capacity fixture exited with " + process.exitValue() + ": " + output);
			return output;
		}
	}

	/**
	 * Verifies that a rejection with a reset time holds until that time without advancing the sequence of hold
	 * lengths.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void rejectionWithResetTimeHoldsUntilIt() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			HarnessCapacity capacity = heldBy(fileLocks, directory, clock);
			Instant resetsAt = HOLD_START.plus(Duration.ofMinutes(45));

			HarnessCapacity.Hold hold = capacity.reject(resetsAt);

			requireThat(hold, "hold").isEqualTo(new HarnessCapacity.Hold(resetsAt, 0));
			clock.set(resetsAt);
			requireThat(capacity.reject(null), "afterReset").isEqualTo(
				new HarnessCapacity.Hold(resetsAt.plus(Duration.ofMinutes(1)), 1));
		}
	}

	/**
	 * Verifies that a hold that ends earlier never replaces one that ends later, whichever instance records it.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void shorterHoldNeverReplacesLongerOne() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			HarnessCapacity first = heldBy(fileLocks, directory, clock);
			HarnessCapacity second = heldBy(fileLocks, directory, clock);
			Instant later = HOLD_START.plus(Duration.ofMinutes(45));
			first.reject(later);

			requireThat(second.reject(HOLD_START.plus(Duration.ofMinutes(10))).endsAt(), "earlierReset").
				isEqualTo(later);
			requireThat(second.reject(null).endsAt(), "withoutReset").isEqualTo(later);
			requireThat(second.refuse().hold().endsAt(), "refusal").isEqualTo(later);
			requireThat(first.hold().endsAt(), "first.hold().endsAt()").isEqualTo(later);
		}
	}

	/**
	 * Verifies that each rejection or refusal that follows the end of a hold doubles the next hold, from 1 minute up
	 * to 30 minutes.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void holdLengthsDoubleUpToThirtyMinutes() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			HarnessCapacity capacity = heldBy(fileLocks, directory, clock);
			List<Long> lengths = new ArrayList<>();
			for (int rejection = 0; rejection < 7; ++rejection)
			{
				Instant now = clock.instant();
				HarnessCapacity.Hold hold;
				if (rejection % 2 == 0)
					hold = capacity.reject(null);
				else
					hold = capacity.refuse().hold();
				lengths.add(Duration.between(now, hold.endsAt()).toMinutes());
				clock.set(hold.endsAt().plusSeconds(5));
			}

			requireThat(lengths, "lengths").isEqualTo(List.of(1L, 2L, 4L, 8L, 16L, 30L, 30L));
		}
	}

	/**
	 * Verifies that a completed turn resets the next hold to 1 minute without ending the hold that lasts.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void completedTurnResetsHoldLength() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			HarnessCapacity first = heldBy(fileLocks, directory, clock);
			HarnessCapacity second = heldBy(fileLocks, directory, clock);
			clock.set(first.reject(null).endsAt());
			HarnessCapacity.Hold lasting = first.reject(null);

			second.completeTurn();

			requireThat(first.hold(), "hold").isEqualTo(new HarnessCapacity.Hold(lasting.endsAt(), 0));
			clock.set(lasting.endsAt());
			requireThat(first.reject(null), "afterReset").isEqualTo(
				new HarnessCapacity.Hold(lasting.endsAt().plus(Duration.ofMinutes(1)), 1));
		}
	}

	/**
	 * Verifies that a rejection or refusal that arrives while a hold lasts neither extends the hold nor lengthens the
	 * next one.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void rejectionDuringHoldDoesNotDouble() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			HarnessCapacity capacity = heldBy(fileLocks, directory, clock);
			HarnessCapacity.Hold first = capacity.reject(null);
			clock.advance(Duration.ofSeconds(30));

			requireThat(capacity.reject(null), "rejectedDuringHold").isEqualTo(first);
			requireThat(capacity.refuse(), "refusedDuringHold").isEqualTo(new HarnessCapacity.Refusal(first, false));
			clock.set(first.endsAt());
			requireThat(capacity.reject(null), "afterHold").isEqualTo(
				new HarnessCapacity.Hold(first.endsAt().plus(Duration.ofMinutes(2)), 2));
		}
	}

	/**
	 * Verifies that only a refusal that follows the end of a 30-minute hold reports that it followed the longest hold.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void refusalAfterLongestHoldIsReported() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			HarnessCapacity capacity = heldBy(fileLocks, directory, clock);
			List<Boolean> afterLongestHold = new ArrayList<>();
			HarnessCapacity.Refusal refusal = null;
			for (int refused = 0; refused < 6; ++refused)
			{
				refusal = capacity.refuse();
				afterLongestHold.add(refusal.afterLongestHold());
				clock.set(refusal.hold().endsAt());
			}
			requireThat(afterLongestHold, "beforeLongestHold").isEqualTo(Collections.nCopies(6, false));
			clock.set(refusal.hold().endsAt().minusSeconds(1));
			requireThat(capacity.refuse().afterLongestHold(), "duringLongestHold").isFalse();

			clock.set(refusal.hold().endsAt());
			requireThat(capacity.refuse().afterLongestHold(), "afterLongestHold").isTrue();
		}
	}

	/**
	 * Verifies that {@code acquire} waits through its pause while a hold lasts and takes a permit once it ends.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void acquireWaitsWhileHoldLasts() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			AtomicInteger pauses = new AtomicInteger();
			Pause advance = () ->
			{
				pauses.incrementAndGet();
				clock.advance(Duration.ofSeconds(20));
			};
			HarnessCapacity capacity = HarnessCapacity.of(fileLocks, directory, 1, advance, clock);
			capacity.reject(null);

			try (HarnessCapacity.Permit permit = capacity.acquire())
			{
				requireThat(permit, "permit").isNotNull();
				requireThat(pauses.get(), "pauses").isEqualTo(3);
			}
		}
	}

	/**
	 * Verifies that holds stay inside their directory: a test-owned directory's hold does not block another directory.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void holdDoesNotCrossDirectories() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			ManualClock clock = new ManualClock(HOLD_START);
			heldBy(fileLocks, root.resolve("first"), clock).reject(null);
			HarnessCapacity second = heldBy(fileLocks, root.resolve("second"), clock);

			requireThat(second.hold(), "second.hold()").isEqualTo(HarnessCapacity.Hold.NONE);
			try (HarnessCapacity.Permit permit = second.tryAcquire().orElseThrow())
			{
				requireThat(permit, "permit").isNotNull();
			}
		}
	}

	/**
	 * Verifies that an unlimited instance records no hold.
	 *
	 * @throws IOException if a permit cannot be closed
	 */
	@Test
	public void unlimitedHasNoHold() throws IOException
	{
		// Requirement: R-E8
		HarnessCapacity capacity = HarnessCapacity.unlimited();

		requireThat(capacity.reject(null), "reject").isEqualTo(HarnessCapacity.Hold.NONE);
		requireThat(capacity.refuse(), "refuse").isEqualTo(new HarnessCapacity.Refusal(HarnessCapacity.Hold.NONE,
			false));
		requireThat(capacity.hold(), "hold").isEqualTo(HarnessCapacity.Hold.NONE);
		try (HarnessCapacity.Permit permit = capacity.tryAcquire().orElseThrow())
		{
			requireThat(permit, "permit").isNotNull();
		}
	}

	/**
	 * Verifies that a hold file that does not match its format is reported with its path instead of being ignored.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void malformedHoldFileIsReported() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			Path file = directory.resolve("hold.json");
			Files.writeString(file, "{\"format_version\":1,\"ends_at\":\"2026-10-06T12:00:00Z\",\"backoffs\":1," +
				"\"extra\":true}");
			HarnessCapacity capacity = heldBy(fileLocks, directory, new ManualClock(HOLD_START));
			try
			{
				capacity.tryAcquire();
				throw new AssertionError("Expected the malformed hold file to be reported");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains(file.toString()).contains("extra");
			}
		}
	}

	/**
	 * Returns an opener that records the name of every lock file it opens before opening it like production does.
	 *
	 * @param opened receives the name of each opened file, in order
	 * @return the opener
	 */
	private static LockFileOpener recording(List<String> opened)
	{
		return file ->
		{
			opened.add(file.getFileName().toString());
			return FileChannel.open(file, CREATE, WRITE);
		};
	}

	/**
	 * Verifies that while one instance holds slot 0, a second instance over the same directory takes slot 1 without
	 * opening slot 0's file. On Linux, closing any channel on a file releases every lock that the process holds on it,
	 * so opening and closing a held slot's file would free the slot for other processes while its permit stays open.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void secondInstanceDoesNotOpenHeldSlot() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity first = HarnessCapacity.of(fileLocks, directory, 2, unexpectedPause(), CLOCK);
			List<String> opened = new CopyOnWriteArrayList<>();
			HarnessCapacity second = HarnessCapacitySecrets.getHarnessCapacityAccess().
				of(fileLocks, directory, 2, unexpectedPause(), CLOCK,
				recording(opened));

			try (HarnessCapacity.Permit held = first.tryAcquire().orElseThrow();
				HarnessCapacity.Permit other = second.tryAcquire().orElseThrow())
			{
				requireThat(held, "held").isNotNull();
				requireThat(other, "other").isNotNull();
				requireThat(opened, "opened").isEqualTo(List.of("slot-1.lock"));
			}
		}
	}

	/**
	 * Supplies which of two held slots is released first.
	 *
	 * @return the index of the slot whose permit closes first
	 */
	@DataProvider
	public Object[][] releasedSlot()
	{
		return new Object[][]
		{
			{0},
			{1}
		};
	}

	/**
	 * Verifies that after one of two held permits closes, a third instance takes the released slot without opening the
	 * slot that is still held, and opens that slot only once its permit closes.
	 *
	 * @param released the slot whose permit closes first
	 * @throws IOException if the filesystem operation fails
	 */
	@Test(dataProvider = "releasedSlot")
	public void releasedPermitDoesNotExposeHeldSlot(int released) throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity first = HarnessCapacity.of(fileLocks, directory, 2, unexpectedPause(), CLOCK);
			HarnessCapacity second = HarnessCapacity.of(fileLocks, directory, 2, unexpectedPause(), CLOCK);
			List<String> opened = new CopyOnWriteArrayList<>();
			HarnessCapacity third = HarnessCapacitySecrets.getHarnessCapacityAccess().
				of(fileLocks, directory, 2, unexpectedPause(), CLOCK,
				recording(opened));
			int kept = 1 - released;

			try (HarnessCapacity.Permit slot0 = first.tryAcquire().orElseThrow();
				HarnessCapacity.Permit slot1 = second.tryAcquire().orElseThrow())
			{
				List<HarnessCapacity.Permit> permits = List.of(slot0, slot1);
				permits.get(released).close();
				try (HarnessCapacity.Permit reacquired = third.tryAcquire().orElseThrow())
				{
					requireThat(reacquired, "reacquired").isNotNull();
					requireThat(opened, "openedWhileHeld").isEqualTo(List.of("slot-" + released + ".lock"));
					requireThat(third.tryAcquire().isPresent(), "acquiredWhileFull").isFalse();
					requireThat(opened, "openedWhileFull").isEqualTo(List.of("slot-" + released + ".lock"));

					permits.get(kept).close();
					try (HarnessCapacity.Permit afterRelease = third.tryAcquire().orElseThrow())
					{
						requireThat(afterRelease, "afterRelease").isNotNull();
						requireThat(opened, "openedAfterRelease").isEqualTo(List.of("slot-" + released + ".lock",
							"slot-" + kept + ".lock"));
					}
				}
			}
		}
	}

	/**
	 * Verifies that {@code tryAcquire} at the maximum opens no slot file that this JVM holds, even when the instance
	 * reaches the directory through a symbolic link.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void tryAcquireAtMaximumOpensNoHeldSlot() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			Path directory = Files.createDirectory(root.resolve("slots"));
			Path alias = Files.createSymbolicLink(root.resolve("alias"), directory);
			HarnessCapacity holder = HarnessCapacity.of(fileLocks, directory, 2, unexpectedPause(), CLOCK);
			List<String> opened = new CopyOnWriteArrayList<>();
			HarnessCapacity sameDirectory = HarnessCapacitySecrets.getHarnessCapacityAccess().
				of(fileLocks, directory, 2, unexpectedPause(), CLOCK,
				recording(opened));
			HarnessCapacity throughAlias = HarnessCapacitySecrets.getHarnessCapacityAccess().
				of(fileLocks, alias, 2, unexpectedPause(), CLOCK,
				recording(opened));

			try (HarnessCapacity.Permit first = holder.tryAcquire().orElseThrow();
				HarnessCapacity.Permit second = holder.tryAcquire().orElseThrow())
			{
				requireThat(first, "first").isNotNull();
				requireThat(second, "second").isNotNull();
				requireThat(sameDirectory.tryAcquire().isPresent(), "acquiredInSameDirectory").isFalse();
				requireThat(throughAlias.tryAcquire().isPresent(), "acquiredThroughAlias").isFalse();
				requireThat(opened, "opened").isEmpty();
			}
		}
	}

	/**
	 * Verifies that an instance that reaches the directory through a symbolic link does not open the hold lock file
	 * while another instance in this JVM holds its lock, but waits and then records its rejection.
	 *
	 * @throws IOException if the filesystem operation fails
	 * @throws InterruptedException if the test thread is interrupted while waiting for the rejecting thread
	 */
	@Test
	public void aliasedHoldUpdateWaitsForHoldLock() throws IOException, InterruptedException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			Path directory = Files.createDirectory(root.resolve("slots"));
			Path alias = Files.createSymbolicLink(root.resolve("alias"), directory);
			List<String> opened = new CopyOnWriteArrayList<>();
			HarnessCapacity second = HarnessCapacitySecrets.getHarnessCapacityAccess().of(fileLocks, alias, 1,
				unexpectedPause(),
				new ManualClock(HOLD_START), recording(opened));
			AtomicReference<Throwable> failure = new AtomicReference<>();
			Thread rejecting = new Thread(() ->
			{
				try
				{
					second.reject(null);
				}
				catch (IOException | RuntimeException e)
				{
					failure.set(e);
				}
			});
			AtomicReference<List<String>> openedWhileHeld = new AtomicReference<>();
			// The first instance reads its clock while it holds the hold lock. At that point the second instance tries
			// to update the hold from another thread, and the first one waits until that thread either waits for the
			// lock or has finished.
			HarnessCapacity first = HarnessCapacity.of(fileLocks, directory, 1, unexpectedPause(),
				new CallbackClock(HOLD_START,
				() ->
				{
					rejecting.start();
					while (rejecting.getState() != Thread.State.BLOCKED &&
						rejecting.getState() != Thread.State.TERMINATED)
					{
						Thread.onSpinWait();
					}
					openedWhileHeld.set(List.copyOf(opened));
				}));

			HarnessCapacity.Hold hold = first.reject(null);
			rejecting.join();

			requireThat(openedWhileHeld.get(), "openedWhileHeld").isEmpty();
			requireThat(failure.get(), "failure").isNull();
			requireThat(opened, "opened").isEqualTo(List.of("hold.lock"));
			requireThat(second.hold(), "second.hold()").isEqualTo(hold);
		}
	}

	/**
	 * A fixed clock that runs an action the first time it is read.
	 */
	private static final class CallbackClock extends Clock
	{
		private final Instant now;
		private final Runnable onFirstRead;
		private final AtomicBoolean read = new AtomicBoolean();

		/**
		 * Creates a clock.
		 *
		 * @param now the time that the clock always reports
		 * @param onFirstRead the action to run the first time the clock is read
		 */
		private CallbackClock(Instant now, Runnable onFirstRead)
		{
			this.now = now;
			this.onFirstRead = onFirstRead;
		}

		@Override
		public ZoneId getZone()
		{
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant()
		{
			if (read.compareAndSet(false, true))
				onFirstRead.run();
			return now;
		}
	}
}
