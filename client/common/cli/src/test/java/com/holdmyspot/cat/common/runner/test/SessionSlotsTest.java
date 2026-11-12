package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.SessionSlots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests {@link SessionSlots}: a slot's path stays the same from one session to the next, a session finds the slot
 * empty, a slot is held by one session at a time, and a slot is not handed on while a process that its previous
 * session recorded remains alive.
 */
public final class SessionSlotsTest
{
	/**
	 * The longest that a test waits for a thread to reach a state or to end.
	 */
	private static final Duration WAIT = Duration.ofSeconds(10);

	/**
	 * Creates the test.
	 */
	public SessionSlotsTest()
	{
	}

	/**
	 * Verifies that the sessions of one slot get the same run root and run identifier, and that the second finds the
	 * slot empty although the first left files in it.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void reusesPathInEmptySlot() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("slots-"));
			SessionSlots slots = new SessionSlots(base, 1, new Liveness());
			SessionSlots.Slot first = slots.acquire(false);
			Files.writeString(Files.createDirectories(first.root().resolve("claude/slot-1/home")).resolve("login"),
				"login");
			slots.release(first);
			SessionSlots.Slot second = slots.acquire(false);

			requireThat(second, "second").isEqualTo(first);
			requireThat(second.root(), "root").isEqualTo(base.resolve("development").resolve("1"));
			requireThat(second.runId(), "runId").isEqualTo("slot-1");
			try (Stream<Path> entries = Files.list(second.root()))
			{
				requireThat(entries.toList(), "entries").isEmpty();
			}
		}
	}

	/**
	 * Verifies that the held-out part has slots of its own, in a directory of their own.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void givesHeldOutSessionsSlotsOfTheirOwn() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("slots-"));
			SessionSlots slots = new SessionSlots(base, 1, new Liveness());
			SessionSlots.Slot development = slots.acquire(false);
			SessionSlots.Slot heldOut = slots.acquire(true);

			requireThat(development.root(), "development").isEqualTo(base.resolve("development").resolve("1"));
			requireThat(heldOut.root(), "heldOut").isEqualTo(base.resolve("held_out").resolve("1"));
			requireThat(heldOut.heldOut(), "heldOutFlag").isTrue();
			requireThat(development.heldOut(), "developmentFlag").isFalse();
		}
	}

	/**
	 * Verifies that a session that finds every slot of its part taken waits until one is released.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void waitsForFreeSlot() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1,
				new Liveness());
			SessionSlots.Slot first = slots.acquire(false);
			AtomicReference<SessionSlots.Slot> second = new AtomicReference<>();
			Thread waiter = acquiring(slots, second);
			awaitWaiting(waiter);
			requireThat(second.get(), "secondWhileTaken").isNull();
			slots.release(first);
			waiter.join(WAIT);

			requireThat(second.get(), "second").isEqualTo(first);
		}
	}

	/**
	 * Verifies that a slot whose previous session's recorded process remains alive is neither emptied nor given to
	 * the next session: the process is asked to end, and the next session takes the slot, emptied, once it has.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void keepsSlotWhileRecordedProcessLives() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot first = slots.acquire(false);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), first.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedRunId(first.runId());
			HarnessRun run = manager.create("claude");
			manager.recordHarnessProcess(run, 4242, "abc");
			slots.release(first);

			requireThat(Files.isDirectory(run.directory()), "keptWhileAlive").isTrue();
			requireThat(liveness.terminations(), "terminations").isGreaterThan(0);
			AtomicReference<SessionSlots.Slot> second = new AtomicReference<>();
			Thread waiter = acquiring(slots, second);
			awaitWaiting(waiter);
			requireThat(second.get(), "secondWhileAlive").isNull();
			liveness.end();
			waiter.join(WAIT);

			requireThat(second.get(), "second").isEqualTo(first);
			try (Stream<Path> entries = Files.list(second.get().root()))
			{
				requireThat(entries.toList(), "entries").isEmpty();
			}
		}
	}

	/**
	 * Verifies that the run that the current process records, which a program that runs a session in itself creates,
	 * does not keep the slot: the process cannot wait for its own end.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void ignoresRunOfCurrentProcess() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot first = slots.acquire(false);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), first.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedRunId(first.runId());
			manager.create("claude");
			slots.release(first);

			requireThat(slots.acquire(false), "second").isEqualTo(first);
			requireThat(liveness.terminations(), "terminations").isEqualTo(0);
		}
	}

	/**
	 * Verifies that a run whose manifest cannot be read records no process that CAT could identify, so it does not
	 * keep the slot.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void ignoresUnreadableManifest() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1,
				new Liveness());
			SessionSlots.Slot first = slots.acquire(false);
			Path run = Files.createDirectories(first.root().resolve("claude").resolve(first.runId()));
			Files.writeString(run.resolve("run.json"), "{not json");
			slots.release(first);

			requireThat(slots.acquire(false), "second").isEqualTo(first);
		}
	}

	/**
	 * Starts a thread that takes a slot of the development part.
	 *
	 * @param slots  the slots
	 * @param result receives the slot
	 * @return the thread
	 */
	private static Thread acquiring(SessionSlots slots, AtomicReference<SessionSlots.Slot> result)
	{
		return Thread.ofVirtual().start(() ->
		{
			try
			{
				result.set(slots.acquire(false));
			}
			catch (IOException e)
			{
				throw new AssertionError(e);
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * Waits until a thread waits for something, which shows that it found no slot to take.
	 *
	 * @param thread the thread
	 */
	private static void awaitWaiting(Thread thread)
	{
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING)
		{
			if (System.nanoTime() > deadline)
				throw new AssertionError("The thread did not wait: " + thread.getState());
			Thread.onSpinWait();
		}
	}

	/**
	 * Tells whether the processes that runs record are alive.
	 */
	private static final class Liveness implements HarnessProcessLiveness
	{
		private final AtomicInteger terminations = new AtomicInteger();
		private volatile boolean alive = true;

		/**
		 * Creates a liveness in which every recorded process lives.
		 */
		private Liveness()
		{
		}

		/**
		 * Makes every recorded process end.
		 */
		private void end()
		{
			alive = false;
		}

		/**
		 * Returns how many times a process was asked to end.
		 *
		 * @return the number of requests
		 */
		private int terminations()
		{
			return terminations.get();
		}

		@Override
		public boolean isAlive(long pid, String start)
		{
			return alive;
		}

		@Override
		public boolean terminate(long pid, String start)
		{
			terminations.incrementAndGet();
			return false;
		}
	}
}
