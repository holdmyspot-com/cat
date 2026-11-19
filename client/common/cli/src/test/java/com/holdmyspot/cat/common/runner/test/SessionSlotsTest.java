package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.UndeletableDirectory;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.runner.SessionSlots;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
	 * The harness session that a retained run records.
	 */
	private static final String SESSION_ID = "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90";
	/**
	 * The harness session that a second retained run records.
	 */
	private static final String OTHER_SESSION_ID = "019a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";

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
			Files.writeString(Files.createDirectories(first.root().resolve("claude/cache/home")).resolve("login"),
				"login");
			slots.release(first);
			SessionSlots.Slot second = slots.acquire(false);

			requireThat(second, "second").isEqualTo(first);
			requireThat(second.root(), "root").isEqualTo(base.resolve("d1"));
			requireThat(second.runId(), "runId").isEqualTo("s-1");
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

			requireThat(development.root(), "development").isEqualTo(base.resolve("d1"));
			requireThat(heldOut.root(), "heldOut").isEqualTo(base.resolve("h1"));
			requireThat(heldOut.heldOut(), "heldOutFlag").isTrue();
			requireThat(development.heldOut(), "developmentFlag").isFalse();
		}
	}

	/**
	 * Verifies that the run of each slot that may be held at once, in both parts, has a path of its own, and that the
	 * part of that path below the slots' base is short: at most 16 characters for a run of a harness named
	 * {@code claude}, against the 41 of {@code development/1/claude/slot-1}.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void givesEachSlotShortPathOfItsOwn() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("slots-"));
			int count = 12;
			SessionSlots slots = new SessionSlots(base, count, new Liveness());
			Set<Path> runs = new HashSet<>();
			for (boolean heldOut : List.of(false, true))
			{
				for (int i = 0; i < count; ++i)
				{
					SessionSlots.Slot slot = slots.acquire(heldOut);
					HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(),
						Yielding.fileReplacer()).withFixedRunId(slot.runId());
					Path run = manager.create("claude").directory();
					requireThat(base.relativize(run).toString().length(), "length").withContext(run, "run").
						isLessThanOrEqualTo(16);
					runs.add(run);
				}
			}

			requireThat(runs, "runs").size().isEqualTo(2 * count);
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
			manager.markHarnessStarting(run);
			manager.recordHarnessProcess(run, 4242, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125");
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
	 * Verifies that a run whose manifest cannot be read keeps its bytes and prevents the slot from being reused.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void refusesUnreadableManifest() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1,
				new Liveness());
			SessionSlots.Slot first = slots.acquire(false);
			Path run = Files.createDirectories(first.root().resolve("claude").resolve(first.runId()));
			Path manifest = run.resolve("run.json");
			Path sentinel = run.resolve("evidence");
			Files.writeString(manifest, "{not json");
			Files.writeString(sentinel, "keep");
			IOException failure = null;
			try
			{
				slots.release(first);
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(failure.getMessage(), "failureMessage").contains(manifest.toString());
			requireThat(manifest, "manifest").isRegularFile();
			requireThat(sentinel, "sentinel").isRegularFile();
		}
	}

	/**
	 * Verifies that a run folder without a manifest, which records no process, does not keep its slot: taking the slot
	 * removes the folder and asks no process to end.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void emptiesRunWithoutManifest() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot first = slots.acquire(false);
			slots.release(first);
			Path run = first.root().resolve("claude").resolve(first.runId());
			for (String entry : List.of("home", "project", "responses"))
				Files.createDirectories(run.resolve(entry));

			requireThat(slots.acquire(false), "second").isEqualTo(first);
			requireThat(Files.exists(run, LinkOption.NOFOLLOW_LINKS), "runExists").isFalse();
			requireThat(liveness.terminations(), "terminations").isEqualTo(0);
		}
	}

	/**
	 * Verifies that a run folder left in a free slot whose manifest is malformed keeps its bytes and prevents the slot
	 * from being taken.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void refusesToTakeSlotWithMalformedManifest() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot first = slots.acquire(false);
			slots.release(first);
			Path run = Files.createDirectories(first.root().resolve("claude").resolve(first.runId()));
			Path manifest = run.resolve("run.json");
			Files.writeString(manifest, "{not json");
			IOException failure = null;
			try
			{
				slots.acquire(false);
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(failure.getMessage(), "failureMessage").contains(manifest.toString());
			requireThat(manifest, "manifest").isRegularFile();
			requireThat(liveness.terminations(), "terminations").isEqualTo(0);
		}
	}

	/**
	 * Verifies that emptying a slot removes each run's manifest after everything else in the run: a deletion that stops
	 * partway, at an entry of the run that cannot be removed, leaves the run's manifest, which names the processes
	 * that a later attempt must check.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void keepsManifestUntilRunIsEmpty() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1,
				new Liveness());
			SessionSlots.Slot slot = slots.acquire(false);
			HarnessRun run = new HarnessRunManager(new FileLocks(), slot.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedRunId(slot.runId()).create("claude");
			Path manifest = run.directory().resolve("run.json");
			IOException failure = null;
			try (UndeletableDirectory _ = UndeletableDirectory.after(run.directory(), "run.json"))
			{
				slots.release(slot);
			}
			catch (IOException e)
			{
				failure = e;
			}

			requireThat(failure, "failure").isNotNull();
			requireThat(manifest, "manifest").isRegularFile();
		}
	}

	/**
	 * Verifies that a slot whose run records a harness session and no end is retained: no session takes it, the next
	 * session takes a slot numbered past the slots' count instead, and no more sessions of the part run at once than
	 * the count allows.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void skipsRetainedSlot() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, new Liveness());
			SessionSlots.Slot first = slots.acquire(false);
			HarnessRun run = interruptedRun(jvm, first, SESSION_ID);
			slots.retain(first);

			requireThat(Files.isDirectory(run.directory().resolve("home")), "kept").isTrue();
			SessionSlots.Slot second = slots.acquire(false);
			requireThat(second.number(), "number").isEqualTo(2);
			requireThat(second.runId(), "runId").isEqualTo("s-2");
			AtomicReference<SessionSlots.Slot> third = new AtomicReference<>();
			Thread waiter = acquiring(slots, third);
			awaitWaiting(waiter);
			requireThat(third.get(), "thirdWhileSecondRuns").isNull();
			slots.release(second);
			waiter.join(WAIT);
			requireThat(third.get(), "third").isEqualTo(second);
			requireThat(Files.isDirectory(run.directory().resolve("home")), "stillKept").isTrue();
		}
	}

	/**
	 * Verifies that a slot whose run recorded no harness session, or recorded the run's end, is emptied for the next
	 * session, as a run that has nothing to resume is.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void emptiesSlotWithNothingToResume() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, new Liveness());
			SessionSlots.Slot first = slots.acquire(false);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), first.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedRunId(first.runId());
			manager.create("claude");
			slots.retain(first);
			requireThat(slots.acquire(false), "unreported").isEqualTo(first);

			HarnessRun ended = manager.create("claude");
			manager.recordHarnessSession(ended, SESSION_ID);
			manager.fail(ended, "The harness exited");
			slots.retain(first);
			requireThat(slots.acquire(false), "ended").isEqualTo(first);
			try (Stream<Path> entries = Files.list(first.root()))
			{
				requireThat(entries.toList(), "entries").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a retained slot of either part is found by the harness session that its run records, that the
	 * process that the run recorded is stopped first, and that the slot keeps its files.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void acquiresRetainedSlotOfEitherPart() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1,
				new ProcessHandleLiveness());
			SessionSlots.Slot development = slots.acquire(false);
			HarnessRun developmentRun = interruptedRun(jvm, development, SESSION_ID);
			try (Process harness = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-cp",
				System.getProperty("java.class.path"), InterruptedRunnerProcess.class.getName(), "harness").start())
			{
				String start = HarnessProcesses.system().find(harness.pid()).flatMap(ProcessDescription::start).
					orElseThrow();
				HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), development.root(),
					Clock.systemUTC(), Yielding.fileReplacer()).withFixedRunId(development.runId());
				manager.markHarnessStarting(developmentRun);
				manager.recordHarnessProcess(developmentRun, harness.pid(), start);
				slots.retain(development);
				SessionSlots.Slot heldOut = slots.acquire(true);
				HarnessRun heldOutRun = interruptedRun(jvm, heldOut, OTHER_SESSION_ID);
				slots.retain(heldOut);

				requireThat(slots.acquireRetained(OTHER_SESSION_ID, "claude"), "heldOut").
					isEqualTo(Optional.of(heldOut));
				requireThat(Files.isDirectory(heldOutRun.directory().resolve("home")), "heldOutKept").isTrue();
				requireThat(slots.acquireRetained(SESSION_ID, "claude"), "development").
					isEqualTo(Optional.of(development));
				requireThat(harness.waitFor(10, TimeUnit.SECONDS), "harnessStopped").isTrue();
				requireThat(Files.isDirectory(developmentRun.directory().resolve("home")), "developmentKept").isTrue();
				requireThat(slots.acquireRetained("unknown-session", "claude"), "unknown").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a retained slot is refused while a launch holds it, while a process that its run recorded stays
	 * alive, when its run is of another harness, and when its manifest is malformed, and that a refusal keeps the
	 * slot's files.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void refusesRetainedSlotThatCannotResume() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 2, liveness);
			SessionSlots.Slot slot = slots.acquire(false);
			HarnessRun run = interruptedRun(jvm, slot, SESSION_ID);
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedRunId(slot.runId());
			manager.markHarnessStarting(run);
			manager.recordHarnessProcess(run, 4242, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125");
			slots.retain(slot);

			SessionRefusedException alive = expectThrows(SessionRefusedException.class,
				() -> slots.acquireRetained(SESSION_ID, "claude"));
			requireThat(alive.getMessage(), "alive").contains("still alive");
			SessionRefusedException harness = expectThrows(SessionRefusedException.class,
				() -> slots.acquireRetained(SESSION_ID, "codex"));
			requireThat(harness.getMessage(), "harness").contains("claude");
			liveness.end();
			SessionSlots.Slot resumed = slots.acquireRetained(SESSION_ID, "claude").orElseThrow();
			SessionRefusedException inUse = expectThrows(SessionRefusedException.class,
				() -> slots.acquireRetained(SESSION_ID, "claude"));
			requireThat(inUse.getMessage(), "inUse").contains("resuming");
			slots.retain(resumed);

			Path manifest = run.directory().resolve("run.json");
			Files.writeString(manifest, Files.readString(manifest).replace("\"schema_version\": 6", "\"schema_version\": 9"));
			expectThrows(IOException.class, () -> slots.acquireRetained(SESSION_ID, "claude"));
			requireThat(Files.isDirectory(run.directory().resolve("home")), "kept").isTrue();
		}
	}

	/**
	 * Verifies that discarding a retained run stops the processes that it recorded and empties its slot, which the
	 * next session then takes, and that a discard is refused while a launch resumes the run.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void discardsRetainedRun() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot slot = slots.acquire(false);
			interruptedRun(jvm, slot, SESSION_ID);
			slots.retain(slot);
			liveness.end();

			SessionSlots.Slot resumed = slots.acquireRetained(SESSION_ID, "claude").orElseThrow();
			SessionRefusedException resuming = expectThrows(SessionRefusedException.class,
				() -> slots.discard(SESSION_ID));
			requireThat(resuming.getMessage(), "resuming").contains("resuming");
			slots.retain(resumed);
			requireThat(slots.discard(SESSION_ID), "discarded").isTrue();
			requireThat(Files.exists(slot.root(), LinkOption.NOFOLLOW_LINKS), "slotExists").isFalse();
			requireThat(slots.discard(SESSION_ID), "discardedAgain").isFalse();
			requireThat(slots.acquire(false), "next").isEqualTo(slot);
		}
	}

	/**
	 * Creates a run in a slot whose harness reported its session and that recorded no end, as a run that its server
	 * stopped leaves behind.
	 *
	 * @param jvm       the test's process scope
	 * @param slot      the slot
	 * @param sessionId the harness session that the run records
	 * @return the run
	 * @throws IOException if the run cannot be created
	 */
	private static HarnessRun interruptedRun(TestJvmScope jvm, SessionSlots.Slot slot, String sessionId)
		throws IOException
	{
		HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(),
			Yielding.fileReplacer()).withFixedRunId(slot.runId());
		HarnessRun run = manager.create("claude");
		manager.recordHarnessSession(run, sessionId);
		return run;
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
