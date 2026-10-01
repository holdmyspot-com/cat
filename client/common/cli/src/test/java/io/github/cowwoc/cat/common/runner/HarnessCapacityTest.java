package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessCapacity}.
 */
public final class HarnessCapacityTest
{
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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity capacity = HarnessCapacity.of(directory, 3, unexpectedPause());

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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity capacity = HarnessCapacity.of(directory, 2, unexpectedPause());

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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity capacity = HarnessCapacity.of(directory, 1, unexpectedPause());
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
	@SuppressWarnings("PMD.CloseResource")
	public void closingPermitTwiceIsHarmless() throws IOException
	{
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity capacity = HarnessCapacity.of(directory, 1, unexpectedPause());
			HarnessCapacity.Permit first = capacity.tryAcquire().orElseThrow();
			first.close();
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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity first = HarnessCapacity.of(directory, 1, unexpectedPause());
			HarnessCapacity second = HarnessCapacity.of(directory, 1, unexpectedPause());
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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity first = HarnessCapacity.of(root.resolve("first"), 1, unexpectedPause());
			HarnessCapacity second = HarnessCapacity.of(root.resolve("second"), 1, unexpectedPause());

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
			HarnessCapacity capacity = HarnessCapacity.of(directory, 1, releaseOnThirdPause);
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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			Pause failing = () ->
			{
				throw new IOException("interrupted");
			};
			HarnessCapacity capacity = HarnessCapacity.of(directory, 1, failing);
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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity.of(directory, HarnessCapacity.MAXIMUM_CONCURRENCY + 1, unexpectedPause());
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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-capacity-"));
			HarnessCapacity.of(directory, 0, unexpectedPause());
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
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-capacity-home-"));
			HarnessCapacity claude = HarnessCapacity.forHome(home, "claude", 1);
			HarnessCapacity codex = HarnessCapacity.forHome(home, "codex", 1);

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
	 * Verifies that the sleeping pause stops waiting with an {@code IOException} when its thread is interrupted, and
	 * keeps the interrupt flag set.
	 */
	@Test
	public void sleepingPauseFailsWhenInterrupted()
	{
		// Requirement: R-E8
		Pause pause = Pause.sleeping(Duration.ofMinutes(1));
		Thread.currentThread().interrupt();
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
}
