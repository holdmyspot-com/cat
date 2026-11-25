package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.UndeletableDirectory;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Status;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
	 * The harness session that a retained nested session records.
	 */
	private static final String SESSION_ID = "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90";
	/**
	 * The harness session that a second retained nested session records.
	 */
	private static final String OTHER_SESSION_ID = "019a2b3c-4d5e-6f70-8192-a3b4c5d6e7f8";

	/**
	 * Creates the test.
	 */
	public SessionSlotsTest()
	{
	}

	/**
	 * Verifies that the sessions of one slot get the same results folder and slot ID, and that the second finds
	 * the slot empty although the first left files in it.
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
			requireThat(second.slotId(), "slotId").isEqualTo("s-1");
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
	 * Verifies that the nested session of each slot that may be held at once, in both parts, has a path of its own, and
	 * that the part of that path below the slots' base is short: at most 16 characters for a nested session of a harness
	 * named {@code claude}, against the 41 of {@code development/1/claude/slot-1}.
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
			Set<Path> sessionFolders = new HashSet<>();
			for (boolean heldOut : List.of(false, true))
			{
				for (int i = 0; i < count; ++i)
				{
					SessionSlots.Slot slot = slots.acquire(heldOut);
					NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(),
						Yielding.fileReplacer()).withFixedSlotId(slot.slotId());
					Path sessionFolder = manager.create("claude").directory();
					requireThat(base.relativize(sessionFolder).toString().length(), "length").
						withContext(sessionFolder, "sessionFolder").
						isLessThanOrEqualTo(16);
					sessionFolders.add(sessionFolder);
				}
			}

			requireThat(sessionFolders, "sessionFolders").size().isEqualTo(2 * count);
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
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), first.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId(first.slotId());
			NestedSession nestedSession = manager.create("claude");
			manager.markHarnessStarting(nestedSession);
			manager.recordHarnessProcess(nestedSession, 4242, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125");
			slots.release(first);

			requireThat(Files.isDirectory(nestedSession.directory()), "keptWhileAlive").isTrue();
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
	 * Verifies that the nested session that the current process records, which a program that runs a session in itself
	 * creates, does not keep the slot: the process cannot wait for its own end.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void ignoresSessionOfCurrentProcess() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot first = slots.acquire(false);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), first.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId(first.slotId());
			manager.create("claude");
			slots.release(first);

			requireThat(slots.acquire(false), "second").isEqualTo(first);
			requireThat(liveness.terminations(), "terminations").isEqualTo(0);
		}
	}

	/**
	 * Verifies that a nested session whose manifest cannot be read keeps its bytes and prevents the slot from being
	 * reused.
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
			Path sessionFolder = Files.createDirectories(first.root().resolve("claude").resolve(first.slotId()));
			Path manifest = sessionFolder.resolve("session.json");
			Path sentinel = sessionFolder.resolve("evidence");
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
	 * Verifies that a session folder without a manifest, which records no process, does not keep its slot: taking the
	 * slot removes the folder and asks no process to end.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void emptiesSessionFolderWithoutManifest() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot first = slots.acquire(false);
			slots.release(first);
			Path sessionFolder = first.root().resolve("claude").resolve(first.slotId());
			for (String entry : List.of("home", "project", "responses"))
				Files.createDirectories(sessionFolder.resolve(entry));

			requireThat(slots.acquire(false), "second").isEqualTo(first);
			requireThat(Files.exists(sessionFolder, LinkOption.NOFOLLOW_LINKS), "runExists").isFalse();
			requireThat(liveness.terminations(), "terminations").isEqualTo(0);
		}
	}

	/**
	 * Verifies that a session folder left in a free slot whose manifest is malformed keeps its bytes and prevents the
	 * slot from being taken.
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
			Path sessionFolder = Files.createDirectories(first.root().resolve("claude").resolve(first.slotId()));
			Path manifest = sessionFolder.resolve("session.json");
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
	 * Verifies that emptying a slot removes each nested session's manifest after everything else in the nested session: a
	 * deletion that stops partway, at an entry of the nested session that cannot be removed, leaves the nested session's
	 * manifest, which names the processes that a later attempt must check.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void keepsManifestUntilFolderIsEmpty() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1,
				new Liveness());
			SessionSlots.Slot slot = slots.acquire(false);
			NestedSession nestedSession = new NestedSessionManager(new FileLocks(), slot.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId(slot.slotId()).create("claude");
			Path manifest = nestedSession.directory().resolve("session.json");
			IOException failure = null;
			try (UndeletableDirectory _ = UndeletableDirectory.after(nestedSession.directory(), "session.json"))
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
	 * Verifies that a slot whose nested session records a harness session and no end is retained: no session takes it,
	 * the next session takes a slot numbered past the slots' count instead, and no more sessions of the part run at once
	 * than the count allows.
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
			NestedSession nestedSession = interruptedSession(jvm, first, SESSION_ID);
			slots.retain(first);

			requireThat(Files.isDirectory(nestedSession.directory().resolve("home")), "kept").isTrue();
			SessionSlots.Slot second = slots.acquire(false);
			requireThat(second.number(), "number").isEqualTo(2);
			requireThat(second.slotId(), "slotId").isEqualTo("s-2");
			AtomicReference<SessionSlots.Slot> third = new AtomicReference<>();
			Thread waiter = acquiring(slots, third);
			awaitWaiting(waiter);
			requireThat(third.get(), "thirdWhileSecondRuns").isNull();
			slots.release(second);
			waiter.join(WAIT);
			requireThat(third.get(), "third").isEqualTo(second);
			requireThat(Files.isDirectory(nestedSession.directory().resolve("home")), "stillKept").isTrue();
		}
	}

	/**
	 * Verifies that a slot whose nested session recorded no harness session, or recorded the nested session's end, is
	 * emptied for the next session, as a nested session that has nothing to resume is.
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
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), first.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId(first.slotId());
			manager.create("claude");
			slots.retain(first);
			requireThat(slots.acquire(false), "unreported").isEqualTo(first);

			NestedSession ended = manager.create("claude");
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
	 * Verifies that a retained slot of either part is found by the harness session that its nested session records, that
	 * the process that the nested session recorded is stopped first, and that the slot keeps its files.
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
			NestedSession developmentSession = interruptedSession(jvm, development, SESSION_ID);
			try (Process harness = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "-cp",
				System.getProperty("java.class.path"), InterruptedRunnerProcess.class.getName(), "harness").start())
			{
				String start = HarnessProcesses.system().find(harness.pid()).flatMap(ProcessDescription::start).
					orElseThrow();
				NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), development.root(),
					Clock.systemUTC(), Yielding.fileReplacer()).withFixedSlotId(development.slotId());
				manager.markHarnessStarting(developmentSession);
				manager.recordHarnessProcess(developmentSession, harness.pid(), start);
				slots.retain(development);
				SessionSlots.Slot heldOut = slots.acquire(true);
				NestedSession heldOutSession = interruptedSession(jvm, heldOut, OTHER_SESSION_ID);
				slots.retain(heldOut);

				requireThat(slots.acquireRetained(OTHER_SESSION_ID, "claude"), "heldOut").
					isEqualTo(Optional.of(heldOut));
				requireThat(Files.isDirectory(heldOutSession.directory().resolve("home")), "heldOutKept").isTrue();
				requireThat(slots.acquireRetained(SESSION_ID, "claude"), "development").
					isEqualTo(Optional.of(development));
				requireThat(harness.waitFor(10, TimeUnit.SECONDS), "harnessStopped").isTrue();
				requireThat(Files.isDirectory(developmentSession.directory().resolve("home")), "developmentKept").isTrue();
				requireThat(slots.acquireRetained("unknown-session", "claude"), "unknown").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a retained slot is refused while a launch holds it, while a process that its nested session recorded
	 * stays alive, when its nested session is of another harness, and when its manifest is malformed, and that a refusal
	 * keeps the slot's files.
	 * <p>
	 * The case retains a real session directory, reacquires its slot, and changes its manifest on disk. Reusing that
	 * directory lets each refusal check the slot and the kept session files across ownership changes.
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
			NestedSession nestedSession = interruptedSession(jvm, slot, SESSION_ID);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId(slot.slotId());
			manager.markHarnessStarting(nestedSession);
			manager.recordHarnessProcess(nestedSession, 4242, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125");
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

			Path manifest = nestedSession.directory().resolve("session.json");
			Files.writeString(manifest, Files.readString(manifest).replace("\"schema_version\": 6", "\"schema_version\": 9"));
			expectThrows(IOException.class, () -> slots.acquireRetained(SESSION_ID, "claude"));
			requireThat(Files.isDirectory(nestedSession.directory().resolve("home")), "kept").isTrue();
		}
	}

	/**
	 * Verifies that discarding a retained nested session stops the processes that it recorded and empties its slot, which
	 * the next session then takes, and that a discard is refused while a launch resumes the nested session.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void discardsRetainedSession() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot slot = slots.acquire(false);
			interruptedSession(jvm, slot, SESSION_ID);
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
	 * Verifies that a launch that resumes a nested session whose session is being stopped waits for that session to give
	 * the slot back, instead of being refused as if another launch resumed the nested session, and then takes the
	 * retained slot.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void resumeWaitsForStoppingSession() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			liveness.end();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot slot = slots.acquire(false);
			interruptedSession(jvm, slot, SESSION_ID);
			slots.markStopping(slot);
			AtomicReference<Object> resumed = new AtomicReference<>();
			Thread resume = running(() -> slots.acquireRetained(SESSION_ID, "claude"), resumed);
			awaitWaiting(resume);
			requireThat(resumed.get(), "resumedWhileStopping").isNull();
			slots.retain(slot);
			resume.join(WAIT);

			requireThat(resumed.get(), "resumed").isEqualTo(Optional.of(slot));
		}
	}

	/**
	 * Verifies that a launch that resumes a nested session whose session is being stopped finds no retained nested
	 * session once that session has ended its nested session normally.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void resumeFindsNoSlotThatAStopEnded() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			liveness.end();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot slot = slots.acquire(false);
			NestedSession nestedSession = interruptedSession(jvm, slot, SESSION_ID);
			slots.markStopping(slot);
			AtomicReference<Object> resumed = new AtomicReference<>();
			Thread resume = running(() -> slots.acquireRetained(SESSION_ID, "claude"), resumed);
			awaitWaiting(resume);
			new NestedSessionManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(), Yielding.fileReplacer()).
				withFixedSlotId(slot.slotId()).markCompleted(nestedSession);
			slots.release(slot);
			resume.join(WAIT);

			requireThat(resumed.get(), "resumed").isEqualTo(Optional.empty());
		}
	}

	/**
	 * Verifies that a waiting resume cannot take a slot that now retains a different session.
	 *
	 * @throws IOException          if writing either session's state fails
	 * @throws InterruptedException if waiting for the resume is interrupted
	 */
	@Test(timeOut = 30_000)
	public void resumeRejectsRecycledSlot() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			Liveness liveness = new Liveness();
			liveness.end();
			SessionSlots slots = new SessionSlots(base, 1, liveness);
			SessionSlots.Slot original = slots.acquire(false);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), original.root(),
				Clock.systemUTC(), Yielding.fileReplacer()).withFixedSlotId(original.slotId());
			NestedSession ended = manager.create("harnessa");
			manager.recordHarnessSession(ended, SESSION_ID);
			slots.markStopping(original);
			AtomicReference<Object> resumed = new AtomicReference<>();
			Thread resume = running(() -> slots.acquireRetained(SESSION_ID, "harnessa"), resumed);
			awaitWaiting(resume);
			synchronized (slots)
			{
				// Simulate the old launch ending and the next launch retaining this slot before the waiter runs
				manager.markCompleted(ended);
				slots.release(original);
				SessionSlots.Slot replacement = slots.acquire(false);
				NestedSession retained = manager.create("harnessa");
				manager.recordHarnessSession(retained, OTHER_SESSION_ID);
				slots.retain(replacement);
			}
			resume.join(WAIT);

			requireThat(resumed.get(), "staleResume").isEqualTo(Optional.empty());
			requireThat(slots.retains(OTHER_SESSION_ID), "replacementRetained").isTrue();
		}
	}

	/**
	 * Verifies that an optimizer claim rechecks the original request, native identity, generation and checkpoint,
	 * and that an old identity cannot discard a slot after another runner has claimed it.
	 *
	 * @throws IOException          if reading or writing the trial data fails
	 * @throws InterruptedException if taking the slot is interrupted
	 */
	@Test
	public void guardedClaimRechecksPrefix() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			liveness.end();
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			SessionSlots slots = new SessionSlots(base, 1, liveness, jvm.fileLocks());
			SessionSlots.Slot slot = slots.acquire(false);
			UUID original = UUID.randomUUID();
			UUID generation = UUID.randomUUID();
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), slot.root(),
				Clock.systemUTC(), Yielding.fileReplacer()).withFixedSlotId(slot.slotId());
			NestedSession nested = manager.create("harnessa");
			manager.recordHarnessSession(nested, SESSION_ID);
			writeTrialBinding(slot, original);
			writeClaimedGeneration(slot, nested, generation, false);
			slots.retain(slot);

			Identity observed = new Identity(generation, Optional.of(new HarnessSession("harnessa", SESSION_ID)));
			RetainedTrialResponse ready = slots.inspectTrial(original);
			requireThat(ready.status(), "readyStatus").isEqualTo(Status.READY);
			requireThat(ready.completedTurns(), "completedTurns").isEqualTo(OptionalInt.of(0));
			requireThat(ready.identity(), "observedIdentity").isEqualTo(Optional.of(observed));
			requireThat(slots.acquireRetained(UUID.randomUUID(), observed, 0), "differentRequest").
				isEqualTo(Optional.empty());
			requireThat(slots.acquireRetained(original, observed, 1), "differentCount").isEqualTo(Optional.empty());
			Identity differentSession = new Identity(generation,
				Optional.of(new HarnessSession("harnessa", OTHER_SESSION_ID)));
			requireThat(slots.acquireRetained(original, differentSession, 0), "differentSession").
				isEqualTo(Optional.empty());

			Path checkpoint = nested.directory().resolve("turn-checkpoint.json");
			String readyCheckpoint = Files.readString(checkpoint);
			Files.writeString(checkpoint, readyCheckpoint.replace("\"ready\"", "\"turn_in_progress\"").
				replace("\"turn_number\":0", "\"turn_number\":1"));
			requireThat(slots.inspectTrial(original).status(), "interruptedTurn").isEqualTo(Status.UNSAFE);
			requireThat(slots.acquireRetained(original, observed, 0), "unreadyCheckpoint").
				isEqualTo(Optional.empty());
			Path manifest = nested.directory().resolve("session.json");
			String initialManifest = Files.readString(manifest);
			Files.writeString(manifest, initialManifest.replace("\"turn_number\": 0", "\"turn_number\": 1"));
			Files.writeString(checkpoint, readyCheckpoint.replace("\"turn_number\":0", "\"turn_number\":1"));
			requireThat(slots.inspectTrial(original).status(), "readyWithoutPrefix").isEqualTo(Status.UNSAFE);
			requireThat(slots.acquireRetained(original, observed, 1), "incompletePrefix").
				isEqualTo(Optional.empty());
			Files.writeString(manifest, initialManifest);
			Files.writeString(checkpoint, readyCheckpoint);
			requireThat(slots.acquireRetained(original, observed, 0), "exactClaim").isEqualTo(Optional.of(slot));
			requireThat(slots.inspectTrial(original).status(), "reservedSlot").isEqualTo(Status.BUSY);
			slots.retain(slot);

			UUID replacement = UUID.randomUUID();
			writeClaimedGeneration(slot, nested, replacement, true);
			requireThat(slots.acquireRetained(original, observed, 0), "oldGeneration").isEqualTo(Optional.empty());
			requireThat(slots.discardTrial(original, Optional.of(observed)).status(), "staleDiscard").
				isEqualTo(Status.UNSAFE);
			RetainedTrialResponse unsafe = slots.inspectTrial(original);
			requireThat(unsafe.status(), "manualResume").isEqualTo(Status.UNSAFE);
			requireThat(unsafe.identity(), "newIdentity").isEqualTo(Optional.of(new Identity(replacement,
				observed.session())));
			requireThat(checkpoint, "checkpointAfterStaleDiscard").isRegularFile();
			requireThat(slots.discardTrial(original, unsafe.identity()).status(), "exactDiscard").
				isEqualTo(Status.DISCARDED);
			requireThat(slots.inspectTrial(original).status(), "afterDiscard").isEqualTo(Status.ABSENT);
		}
	}

	/**
	 * Supplies launch-request records that a slot cannot trust.
	 *
	 * @return the record's content
	 */
	@DataProvider
	public Object[][] invalidLaunchRequests()
	{
		String id = UUID.randomUUID().toString();
		return new Object[][]
		{
			{"{\"format_version\":1,\"request_id\":"},
			{"{\"format_version\":2,\"request_id\":\"" + id + "\"}"},
			{"{\"format_version\":1,\"session\":\"" + id + "\"}"},
			{"{\"format_version\":1,\"request_id\":\"../escape\"}"}
		};
	}

	/**
	 * Verifies that a slot whose launch-request record is malformed, has another format version, or has no valid
	 * request ID is refused, instead of being treated as unrelated to the trial.
	 *
	 * @param record the content of the slot's launch-request record
	 * @throws IOException          if reading or writing the slot fails
	 * @throws InterruptedException if taking the slot is interrupted
	 */
	@Test(dataProvider = "invalidLaunchRequests")
	public void refusesInvalidLaunchRequestRecord(String record) throws IOException, InterruptedException
	{
		// Requirement: R-I12
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			SessionSlots slots = new SessionSlots(base, 1, new Liveness(), jvm.fileLocks());
			SessionSlots.Slot slot = slots.acquire(false);
			Path file = slot.root().resolve("launch-request.json");
			Files.writeString(file, record);
			slots.retain(slot);

			IOException refused = expectThrows(IOException.class, () -> slots.inspectTrial(UUID.randomUUID()));
			requireThat(refused.getMessage(), "message").isEqualTo("Invalid original launch request record: " + file);
			expectThrows(IOException.class, () -> slots.discardTrial(UUID.randomUUID(), Optional.empty()));
		}
	}

	/**
	 * Verifies that a request that more than one slot records is refused, instead of one of the slots being chosen.
	 *
	 * @throws IOException          if reading or writing the slots fails
	 * @throws InterruptedException if taking a slot is interrupted
	 */
	@Test
	public void refusesRequestBoundByMoreThanOneSlot() throws IOException, InterruptedException
	{
		// Requirement: R-I12
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			SessionSlots slots = new SessionSlots(base, 2, new Liveness(), jvm.fileLocks());
			SessionSlots.Slot first = slots.acquire(false);
			SessionSlots.Slot second = slots.acquire(false);
			UUID original = UUID.randomUUID();
			writeTrialBinding(first, original);
			writeTrialBinding(second, original);
			slots.retain(first);
			slots.retain(second);

			IOException refused = expectThrows(IOException.class, () -> slots.inspectTrial(original));
			requireThat(refused.getMessage(), "message").
				isEqualTo("More than one slot records original launch request " + original);
			requireThat(slots.inspectTrial(UUID.randomUUID()).status(), "unrelatedRequest").isEqualTo(Status.ABSENT);
		}
	}

	/**
	 * Verifies that a launch waiting for its runner can be removed only after its exact broker process disappears.
	 *
	 * @throws IOException          if reading or writing the trial data fails
	 * @throws InterruptedException if taking the slot is interrupted
	 */
	@Test
	public void pendingTrialWaitsForBrokerDeath() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			SessionSlots slots = new SessionSlots(base, 1, liveness, jvm.fileLocks());
			SessionSlots.Slot slot = slots.acquire(false);
			UUID original = UUID.randomUUID();
			UUID generation = UUID.randomUUID();
			writeTrialBinding(slot, original);
			ObjectNode pending = startupGeneration(generation, "pending");
			Files.writeString(slot.root().resolve("startup-permit.json"), pending.toString());
			slots.retain(slot);

			Optional<Identity> expected = Optional.of(new Identity(generation, Optional.empty()));
			requireThat(slots.inspectTrial(original).status(), "liveBroker").isEqualTo(Status.BUSY);
			requireThat(slots.discardTrial(original, expected).status(), "pendingDiscard").isEqualTo(Status.BUSY);

			liveness.end();
			RetainedTrialResponse unsafe = slots.inspectTrial(original);
			requireThat(unsafe.status(), "expiredPermit").isEqualTo(Status.UNSAFE);
			requireThat(unsafe.identity(), "expiredGeneration").isEqualTo(expected);
			requireThat(slots.discardTrial(original, expected).status(), "settledDiscard").
				isEqualTo(Status.DISCARDED);
			requireThat(slots.inspectTrial(original).status(), "afterDiscard").isEqualTo(Status.ABSENT);
		}
	}

	/**
	 * Verifies that recovery stops only the exact claimed runner after its broker is gone.
	 *
	 * @throws IOException          if reading or writing the slot fails
	 * @throws InterruptedException if taking the slot is interrupted
	 */
	@Test
	public void probeStopsOrphanedRunner() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Map<Long, String> live = new HashMap<>();
			Map<Long, String> stopped = new HashMap<>();
			HarnessProcessLiveness liveness = new HarnessProcessLiveness()
			{
				@Override
				public boolean isAlive(long pid, String start)
				{
					return start.equals(live.get(pid));
				}

				@Override
				public boolean terminate(long pid, String start)
				{
					if (!live.remove(pid, start))
						return false;
					stopped.put(pid, start);
					return true;
				}
			};
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			SessionSlots slots = new SessionSlots(base, 1, liveness, jvm.fileLocks());
			SessionSlots.Slot slot = slots.acquire(false);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), slot.root(),
				Clock.systemUTC(), Yielding.fileReplacer()).withFixedSlotId(slot.slotId());
			NestedSession nested = manager.create("harnessa");
			manager.recordHarnessSession(nested, SESSION_ID);
			UUID original = UUID.randomUUID();
			UUID generation = UUID.randomUUID();
			writeTrialBinding(slot, original);
			long brokerPid = ProcessHandle.current().pid();
			long runnerPid = brokerPid + 1;
			String brokerStart = HarnessProcesses.system().find(brokerPid).flatMap(ProcessDescription::start).
				orElseThrow();
			String runnerStart = "r1";
			Path manifest = nested.directory().resolve("session.json");
			Files.writeString(manifest, Files.readString(manifest).
				replace("\"owner_pid\": " + brokerPid, "\"owner_pid\": " + runnerPid).
				replace("\"owner_started_at\": \"" + brokerStart + "\"", "\"owner_started_at\": \"r1\""));
			writeClaimedGeneration(slot, nested, generation, false);
			slots.retain(slot);
			live.put(brokerPid, brokerStart);
			live.put(runnerPid, runnerStart);

			requireThat(slots.inspectTrial(original).status(), "ownedRunner").isEqualTo(Status.BUSY);
			requireThat(stopped, "beforeBrokerExit").isEmpty();

			live.put(brokerPid, brokerStart + "1");
			requireThat(slots.inspectTrial(original).status(), "stoppingRunner").isEqualTo(Status.BUSY);
			requireThat(stopped, "terminatedProcesses").isEqualTo(Map.of(runnerPid, runnerStart));
			requireThat(slots.inspectTrial(original).status(), "stoppedRunner").isEqualTo(Status.READY);
		}
	}

	/**
	 * Verifies that a public resume can take a retained session when an earlier broker died before its resume started.
	 *
	 * @throws IOException          if reading or writing the slot fails
	 * @throws InterruptedException if taking the slot is interrupted
	 */
	@Test
	public void publicResumeRevokesStaleStartup() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			SessionSlots slots = new SessionSlots(base, 1, liveness, jvm.fileLocks());
			SessionSlots.Slot slot = slots.acquire(false);
			NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), slot.root(),
				Clock.systemUTC(), Yielding.fileReplacer()).withFixedSlotId(slot.slotId());
			NestedSession nested = manager.create("harnessa");
			manager.recordHarnessSession(nested, SESSION_ID);
			UUID generation = UUID.randomUUID();
			Path permit = slot.root().resolve("startup-permit.json");
			Files.writeString(permit, startupGeneration(generation, "pending").toString());
			slots.retain(slot);

			expectThrows(SessionRefusedException.class, () -> slots.acquireRetained(SESSION_ID, "harnessa"));
			liveness.end();
			requireThat(slots.acquireRetained(SESSION_ID, "harnessa"), "resumeAfterBrokerDeath").
				isEqualTo(Optional.of(slot));
			JsonNode cancelled = JsonMapper.builder().build().readTree(Files.readString(permit));
			requireThat(cancelled.path("generation").asString(), "cancelledGeneration").isEqualTo(generation.toString());
			requireThat(cancelled.path("state").asString(), "cancelledState").isEqualTo("cancelled");
			slots.release(slot);
		}
	}

	/**
	 * Verifies that a slot whose launch state stays uncertain after its owner is gone is left untouched when its
	 * session ends: nothing in it is removed or signalled, and the next session takes another slot.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void leavesStuckStartingSlotUntouched() throws IOException, InterruptedException
	{
		// Requirement: R-I12
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot first = slots.acquire(false);
			NestedSession nested = startingSession(jvm, first, true);
			Path sentinel = Files.writeString(nested.directory().resolve("evidence"), "keep");
			liveness.end();

			slots.release(first);

			requireThat(nested.directory().resolve("session.json"), "manifest").isRegularFile();
			requireThat(sentinel, "sentinel").isRegularFile();
			SessionSlots.Slot second = slots.acquire(false);
			requireThat(second.number(), "number").isEqualTo(2);
			requireThat(second.slotId(), "slotId").isEqualTo("s-2");
			requireThat(liveness.terminations(), "terminations").isEqualTo(0);
			requireThat(sentinel, "sentinelAfterNextSession").isRegularFile();
		}
	}

	/**
	 * Supplies whether the launcher that recorded an uncertain launch is the current process.
	 *
	 * @return the owner choices
	 */
	@DataProvider
	public static Object[][] ownersThatAreNotGone()
	{
		return new Object[][]{{true}, {false}};
	}

	/**
	 * Verifies that an uncertain launch whose owner is not gone still refuses to empty its slot.
	 *
	 * @param ownerIsCurrentProcess {@code true} if the launcher is this process, or {@code false} if it is another
	 *                              process that lives
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(dataProvider = "ownersThatAreNotGone", timeOut = 30_000)
	public void refusesStartingSlotWhileOwnerLives(boolean ownerIsCurrentProcess)
		throws IOException, InterruptedException
	{
		// Requirement: R-I12
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, new Liveness());
			SessionSlots.Slot first = slots.acquire(false);
			NestedSession nested = startingSession(jvm, first, !ownerIsCurrentProcess);

			IOException failure = expectThrows(IOException.class, () -> slots.release(first));

			requireThat(failure.getMessage(), "failure").contains("Harness launch state is unknown");
			requireThat(nested.directory().resolve("session.json"), "manifest").isRegularFile();
		}
	}

	/**
	 * Verifies that the broker names the slot of a trial whose launch stays uncertain with its owner gone, for a status
	 * and for a discard, and leaves the slot untouched, so that the caller reruns the attempt in another slot.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void reportsStuckTrialWithoutEmptyingIt() throws IOException, InterruptedException
	{
		// Requirement: R-I12
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			SessionSlots slots = new SessionSlots(base, 1, liveness, jvm.fileLocks());
			SessionSlots.Slot slot = slots.acquire(false);
			NestedSession nested = startingSession(jvm, slot, true);
			Path sentinel = Files.writeString(nested.directory().resolve("evidence"), "keep");
			UUID original = UUID.randomUUID();
			UUID generation = UUID.randomUUID();
			writeTrialBinding(slot, original);
			writeClaimedGeneration(slot, nested, generation, false);
			slots.retain(slot);
			liveness.end();

			RetainedTrialResponse status = slots.inspectTrial(original);
			requireThat(status.status(), "status").isEqualTo(Status.STUCK);
			requireThat(status.stuckSlot(), "statusSlot").isEqualTo(Optional.of(slot.root()));
			RetainedTrialResponse discard = slots.discardTrial(original,
				Optional.of(new Identity(generation, Optional.empty())));
			requireThat(discard.status(), "discard").isEqualTo(Status.STUCK);
			requireThat(discard.stuckSlot(), "discardSlot").isEqualTo(Optional.of(slot.root()));

			requireThat(nested.directory().resolve("session.json"), "manifest").isRegularFile();
			requireThat(sentinel, "sentinel").isRegularFile();
			requireThat(liveness.terminations(), "terminations").isEqualTo(0);
			requireThat(slots.acquire(false).number(), "nextSlot").isEqualTo(2);
		}
	}

	/**
	 * Verifies that the broker keeps refusing an uncertain launch whose owner lives, as an unresolved status.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void refusesTrialWhoseStartingOwnerLives() throws IOException, InterruptedException
	{
		// Requirement: R-I12
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "slots-"));
			SessionSlots slots = new SessionSlots(base, 1, new Liveness(), jvm.fileLocks());
			SessionSlots.Slot slot = slots.acquire(false);
			NestedSession nested = startingSession(jvm, slot, true);
			UUID original = UUID.randomUUID();
			writeTrialBinding(slot, original);
			writeClaimedGeneration(slot, nested, UUID.randomUUID(), false);
			slots.retain(slot);

			Status status = null;
			try
			{
				status = slots.inspectTrial(original).status();
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "failure").contains("Harness launch state is unknown");
			}
			requireThat(status, "status").isNotEqualTo(Status.STUCK);
		}
	}

	/**
	 * Creates a nested session in a slot whose launch is recorded as possibly having a child, with no harness identity.
	 *
	 * @param jvm     the test's JVM scope
	 * @param slot    the slot
	 * @param otherOwner {@code true} to record a launcher other than this process
	 * @return the nested session
	 * @throws IOException if the session cannot be written
	 */
	private static NestedSession startingSession(TestJvmScope jvm, SessionSlots.Slot slot, boolean otherOwner)
		throws IOException
	{
		NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(),
			Yielding.fileReplacer()).withFixedSlotId(slot.slotId());
		NestedSession nested = manager.create("claude");
		manager.markHarnessStarting(nested);
		if (otherOwner)
		{
			long current = ProcessHandle.current().pid();
			String currentStart = HarnessProcesses.system().find(current).flatMap(ProcessDescription::start).orElseThrow();
			Path manifest = nested.directory().resolve("session.json");
			Files.writeString(manifest, Files.readString(manifest).
				replace("\"owner_pid\": " + current, "\"owner_pid\": " + (current + 1)).
				replace("\"owner_started_at\": \"" + currentStart + "\"", "\"owner_started_at\": \"gone\""));
		}
		return nested;
	}

	/**
	 * Writes the original archive binding that a broker commits before attempting to start a runner.
	 *
	 * @param slot      the acquired slot
	 * @param requestId the original launch archive
	 * @throws IOException if the binding cannot be written
	 */
	private static void writeTrialBinding(SessionSlots.Slot slot, UUID requestId) throws IOException
	{
		ObjectNode binding = JsonMapper.builder().build().createObjectNode().put("format_version", 1).
			put("request_id", requestId.toString());
		Files.writeString(slot.root().resolve("launch-request.json"), binding.toString());
	}

	/**
	 * Simulates claiming a generation with the exact runner identity produced by a real nested session manager.
	 *
	 * @param slot         the acquired slot
	 * @param nested       the nested session the runner published
	 * @param generation   the startup generation
	 * @param manualResume whether a public resume has used the slot
	 * @throws IOException if either ownership record cannot be read or written
	 */
	private static void writeClaimedGeneration(SessionSlots.Slot slot, NestedSession nested, UUID generation,
		boolean manualResume) throws IOException
	{
		JsonNode manifest = JsonMapper.builder().build().readTree(
			Files.readString(nested.directory().resolve("session.json")));
		ObjectNode claimed = startupGeneration(generation, "claimed").put("manual_resume", manualResume).
			put("runner_pid", manifest.path("owner_pid").longValue()).
			put("runner_started_at", manifest.path("owner_started_at").asString());
		Files.writeString(slot.root().resolve("startup-permit.json"), claimed.toString());
	}

	/**
	 * Builds a startup record for a test that determines process liveness without spawning a runner.
	 *
	 * @param generation the startup generation
	 * @param state      its startup state
	 * @return the simulated startup record
	 * @throws IOException if the broker's process start cannot be read
	 */
	private static ObjectNode startupGeneration(UUID generation, String state) throws IOException
	{
		long pid = ProcessHandle.current().pid();
		String start = HarnessProcesses.system().find(pid).flatMap(ProcessDescription::start).orElseThrow();
		return JsonMapper.builder().build().createObjectNode().put("format_version", 1).
			put("generation", generation.toString()).put("broker_pid", pid).put("broker_started_at", start).
			put("state", state).put("manual_resume", false).put("runner_pid", 0).putNull("runner_started_at");
	}

	/**
	 * Verifies that discarding a nested session whose session is being stopped waits for that session to give the slot
	 * back, instead of being refused as if a launch resumed the nested session, and then empties the slot.
	 *
	 * @throws IOException          if the filesystem operation fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void discardWaitsForStoppingSession() throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Liveness liveness = new Liveness();
			liveness.end();
			SessionSlots slots = new SessionSlots(cleanup.add(Files.createTempDirectory("slots-")), 1, liveness);
			SessionSlots.Slot slot = slots.acquire(false);
			interruptedSession(jvm, slot, SESSION_ID);
			slots.markStopping(slot);
			AtomicReference<Object> discarded = new AtomicReference<>();
			Thread discard = running(() -> slots.discard(SESSION_ID), discarded);
			awaitWaiting(discard);
			requireThat(discarded.get(), "discardedWhileStopping").isNull();
			slots.retain(slot);
			discard.join(WAIT);

			requireThat(discarded.get(), "discarded").isEqualTo(true);
			requireThat(Files.exists(slot.root(), LinkOption.NOFOLLOW_LINKS), "slotExists").isFalse();
		}
	}

	/**
	 * Creates a nested session in a slot whose harness reported its session and that recorded no end, as a nested session
	 * that its server stopped leaves behind.
	 *
	 * @param jvm       the test's process scope
	 * @param slot      the slot
	 * @param sessionId the harness session that the nested session records
	 * @return the nested session
	 * @throws IOException if the nested session cannot be created
	 */
	private static NestedSession interruptedSession(TestJvmScope jvm, SessionSlots.Slot slot, String sessionId)
		throws IOException
	{
		NestedSessionManager manager = new NestedSessionManager(jvm.fileLocks(), slot.root(), Clock.systemUTC(),
			Yielding.fileReplacer()).withFixedSlotId(slot.slotId());
		NestedSession nestedSession = manager.create("claude");
		manager.recordHarnessSession(nestedSession, sessionId);
		return nestedSession;
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
	 * Starts a thread that runs an action on the slots.
	 *
	 * @param action the action
	 * @param result receives the action's result, or the exception that it threw
	 * @return the thread
	 */
	private static Thread running(Callable<?> action, AtomicReference<Object> result)
	{
		return Thread.ofVirtual().start(() ->
		{
			try
			{
				result.set(action.call());
			}
			catch (Exception e)
			{
				result.set(e);
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
			if (thread.getState() == Thread.State.TERMINATED || System.nanoTime() > deadline)
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
