package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.ExclusiveFileLock;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Status;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fixed set of session folders, one for each requested session that may run at once, which a requested session
 * takes for the whole of its nested session. Every session builds its nested session in a slot at the same path as the
 * session before it, so the paths of its home, harness configuration directory and project repeat from one session to
 * the next, while no file of the earlier session remains.
 * <p>
 * A slot belongs to either the development part or the held-out part. The two parts have slots of their own, in
 * separate directories, so that a session of one part never runs where a session of the other did.
 * <p>
 * The paths are short: {@code d1}, {@code d2}, and so on, for the development part's slots and {@code h1},
 * {@code h2}, and so on, for the held-out part's, each nested session below its harness's name under the identifier
 * {@code s-<number>}. Every path of a session's home and project starts with its nested session's path, and a harness
 * repeats those paths many times in what it sends the model, in its sandbox description among others, and again for
 * each level of requested sessions. The names stay distinct across parts and numbers, and none is a name that Windows
 * reserves.
 * <p>
 * A slot is emptied before a session takes it, and again once the session has ended, but a slot is never emptied or
 * handed on while a process that its previous session recorded in its nested session's manifest remains alive: CAT
 * stops each such process, and if one stays alive, the slot waits. The manifest records only the runner's process and
 * the harness process, not the processes below them. A session folder without a manifest records no process, so
 * emptying the slot removes it without stopping anything; a manifest that cannot be read or is malformed keeps the slot
 * from being emptied.
 * <p>
 * A slot whose nested session records the session that its harness reported, and no end, is <b>retained</b>: its
 * session was interrupted and may continue, so no other session takes the slot, and a session that needs a slot of the
 * part takes the next slot instead, numbered past the slots' count if need be. No more sessions of a part run at once
 * than the count allows. A retained slot is given only to the launch that resumes its nested session's harness session,
 * and is emptied only when that launch ends its nested session or the nested session is discarded. A resume or a
 * discard of a nested session whose session is still being stopped waits for that session to give the slot back.
 * <p>
 * A slot whose nested session recorded that a harness child may exist, and never recorded the child's identity or its
 * absence, while the process that launched it is gone, is <b>stuck</b>: its launch state cannot become known. CAT
 * leaves a stuck slot untouched, never empties it and signals nothing in it, and no session takes it. The attempt that
 * the slot held is run again in another slot, and the user removes the stuck slot by hand.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class SessionSlots
{
	/**
	 * The start of the name of each development slot's directory below the slots' base, which the slot's number
	 * follows.
	 */
	static final String DEVELOPMENT = "d";
	/**
	 * The start of the name of each held-out slot's directory below the slots' base, which the slot's number follows.
	 */
	static final String HELD_OUT = "h";
	/**
	 * The start of the identifier of each slot's nested session, which the slot's number follows.
	 */
	private static final String SLOT_ID = "s-";
	/**
	 * How long a request waits for a slot to be released before it checks again, in milliseconds.
	 */
	private static final long RELEASE_WAIT_MILLIS = 1_000;
	/**
	 * How long a request waits for the processes of a slot's previous session to end before it checks again, in
	 * milliseconds.
	 */
	private static final long PROCESS_WAIT_MILLIS = 100;
	/**
	 * How long a resume or a discard waits for the processes that a retained nested session recorded to end once they are
	 * asked to, in milliseconds.
	 */
	private static final long STOP_WAIT_MILLIS = 1_000;
	/**
	 * The name of a slot's directory below the slots' base.
	 */
	private static final Pattern SLOT_NAME = Pattern.compile("(?<part>[" + DEVELOPMENT + HELD_OUT +
		"])(?<number>[1-9][0-9]{0,8})");
	/**
	 * Matches the start of a path that lies in a slot's nested session: the slot's directory, the harness's directory
	 * and the nested session, whose identifier ends with the slot's number. Either kind of separator may divide the
	 * names, and any directories may lead to the slot's directory.
	 */
	private static final Pattern NESTED_SESSION_IN_SLOT = Pattern.compile("^(?:.*[/\\\\])?[" + DEVELOPMENT +
		HELD_OUT + "](?<number>[1-9][0-9]{0,8})[/\\\\][^/\\\\]+[/\\\\]" + SLOT_ID +
		"\\k<number>(?=[/\\\\]|$)");
	/**
	 * The maximum size of an original request record, which holds a format version and one archive identifier.
	 */
	private static final int MAXIMUM_BINDING_BYTES = 512;
	/**
	 * Reads the private original launch record without accepting ambiguous JSON.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).
		enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
	private final Path base;
	private final int count;
	private final HarnessProcessLiveness liveness;
	private final FileLocks fileLocks;
	private final BitSet developmentTaken;
	private final BitSet heldOutTaken;
	/**
	 * The taken slots whose sessions are being stopped, which give them back once they have ended.
	 */
	private final Set<Slot> stopping = new HashSet<>();

	/**
	 * A slot that a session holds.
	 *
	 * @param root    the directory that the session uses as its results folder, which holds its nested session below the
	 *                harness's name
	 * @param slotId  the identifier of the session's nested session, which is the same for every session of the slot
	 * @param heldOut {@code true} if the slot belongs to the held-out part
	 * @param number  the slot's number within its part, starting at {@code 1}
	 */
	public record Slot(Path root, String slotId, boolean heldOut, int number)
	{
		/**
		 * Creates a slot.
		 *
		 * @param root    the directory that the session uses as its results folder
		 * @param slotId  the identifier of the session's nested session
		 * @param heldOut {@code true} if the slot belongs to the held-out part
		 * @param number  the slot's number within its part
		 * @throws NullPointerException     if {@code root} or {@code slotId} are null
		 * @throws IllegalArgumentException if {@code number} is not positive
		 */
		public Slot
		{
			requireThat(root, "root").isNotNull();
			requireThat(slotId, "slotId").isNotNull();
			requireThat(number, "number").isPositive();
		}
	}

	/**
	 * Creates the slots of a server or a nested session, which exist as directories only while sessions hold them.
	 *
	 * @param base     the absolute directory below which the slots live
	 * @param count    how many sessions of each part may run at once
	 * @param liveness checks and stops the processes that a session's nested session records
	 * @throws NullPointerException     if {@code base} or {@code liveness} are null
	 * @throws IllegalArgumentException if {@code base} is relative or {@code count} is not positive
	 */
	public SessionSlots(Path base, int count, HarnessProcessLiveness liveness)
	{
		this(base, count, liveness, new FileLocks());
	}

	/**
	 * Creates a site's slots using the process's shared coordinator for runner startup and slot cleanup.
	 *
	 * @param base      the absolute directory below which the slots live
	 * @param count     how many sessions of each part may run at once
	 * @param liveness  checks and stops the processes that a slot's nested session records
	 * @param fileLocks the coordinator shared by the processes' local lock users
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code base} is relative or {@code count} is not positive
	 */
	public SessionSlots(Path base, int count, HarnessProcessLiveness liveness, FileLocks fileLocks)
	{
		requireThat(base, "base").isNotNull().isAbsolute();
		requireThat(count, "count").isPositive();
		requireThat(liveness, "liveness").isNotNull();
		requireThat(fileLocks, "fileLocks").isNotNull();
		this.base = base;
		this.count = count;
		this.liveness = liveness;
		this.fileLocks = fileLocks;
		this.developmentTaken = new BitSet(count);
		this.heldOutTaken = new BitSet(count);
	}

	/**
	 * Returns how many sessions of each part may run at once.
	 *
	 * @return the number of slots in each part
	 */
	public int count()
	{
		return count;
	}

	/**
	 * Waits until fewer sessions of a part run than the slots' count allows, then takes the free slot of the part with
	 * the lowest number that is not retained, waits for its previous session to end, empties it and gives it to the
	 * caller. The slot stays the caller's until it is {@link #release(Slot) released} or {@link #retain(Slot)
	 * retained}.
	 *
	 * @param heldOut {@code true} to take a slot of the held-out part, or {@code false} for the development part
	 * @return the slot, whose directory exists and is empty
	 * @throws IOException          if the slot cannot be emptied
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	public Slot acquire(boolean heldOut) throws IOException, InterruptedException
	{
		Files.createDirectories(base);
		BitSet taken = taken(heldOut);
		while (true)
		{
			Slot slot;
			synchronized (this)
			{
				while (taken.cardinality() >= count)
					wait(RELEASE_WAIT_MILLIS);
				int index = taken.nextClearBit(0);
				while (isRetained(slot(heldOut, index + 1)) || isStuck(slot(heldOut, index + 1)))
					index = taken.nextClearBit(index + 1);
				taken.set(index);
				slot = slot(heldOut, index + 1);
			}
			boolean ready = false;
			try
			{
				if (empty(slot))
				{
					Files.createDirectories(slot.root());
					ready = true;
				}
			}
			finally
			{
				if (!ready)
					free(slot);
			}
			if (ready)
				return slot;
			// Every free slot of the part may hold a process of an earlier session, so wait for it to end
			synchronized (this)
			{
				wait(PROCESS_WAIT_MILLIS);
			}
		}
	}

	/**
	 * Gives a slot back once its session has ended and its results are copied out. The slot is emptied unless a
	 * process of the session remains alive, in which case the next session to take it empties it after that process
	 * ends.
	 *
	 * @param slot the slot that {@link #acquire(boolean)} returned
	 * @throws NullPointerException if {@code slot} is null
	 * @throws IOException          if the slot cannot be emptied
	 */
	public void release(Slot slot) throws IOException
	{
		requireThat(slot, "slot").isNotNull();
		try
		{
			empty(slot);
		}
		finally
		{
			free(slot);
		}
	}

	/**
	 * Gives a slot back without emptying it, once its session was interrupted. The slot stays retained while its nested
	 * session records its harness session and no end; a slot whose nested session does not is emptied by the next session
	 * to take it.
	 *
	 * @param slot the slot that {@link #acquire(boolean)} or {@link #acquireRetained(String, String)} returned
	 * @throws NullPointerException if {@code slot} is null
	 */
	public void retain(Slot slot)
	{
		requireThat(slot, "slot").isNotNull();
		free(slot);
	}

	/**
	 * Records that the session that holds a slot is being stopped, so that a launch that resumes or discards the slot's
	 * nested session waits for the session to give the slot back instead of being refused. The record ends when the slot
	 * is {@link #release(Slot) released} or {@link #retain(Slot) retained}.
	 *
	 * @param slot the slot that {@link #acquire(boolean)} or {@link #acquireRetained(String, String)} returned
	 * @throws NullPointerException if {@code slot} is null
	 */
	public void markStopping(Slot slot)
	{
		requireThat(slot, "slot").isNotNull();
		synchronized (this)
		{
			if (taken(slot.heldOut()).get(slot.number() - 1))
				stopping.add(slot);
		}
	}

	/**
	 * Takes the retained slot whose nested session records a harness session, so that a launch continues the session
	 * there. Waits until a session that holds the slot and is being stopped gives it back and until fewer sessions of the
	 * slot's part run than the slots' count allows, and stops each process that the nested session recorded, under the
	 * checks of its identity. The slot keeps its files, and stays the caller's until it is {@link #release(Slot)
	 * released} or {@link #retain(Slot) retained}.
	 *
	 * @param sessionId the session that the harness reported
	 * @param harness   the harness of the launch that resumes the session
	 * @return the slot, or none if no retained slot records the session, including when the session that held the slot
	 *   ended the nested session
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the slot's nested session is of another harness, a launch that is not being
	 *                                 stopped holds the slot, or a process that the nested session recorded stays alive
	 *                                 once it is asked to end
	 * @throws IOException             if no readable nested session records the session and a nested session's manifest
	 *                                 cannot be read, or the slot's nested session cannot be checked
	 * @throws InterruptedException    if the thread is interrupted while it waits
	 */
	public Optional<Slot> acquireRetained(String sessionId, String harness) throws IOException, InterruptedException
	{
		requireThat(sessionId, "sessionId").isNotNull();
		requireThat(harness, "harness").isNotNull();
		Optional<Retained> found = findRetained(sessionId);
		if (found.isEmpty())
			return Optional.empty();
		Slot slot = found.get().slot();
		String recorded = found.get().harness();
		if (!recorded.equals(harness))
		{
			throw new SessionRefusedException("The session " + sessionId + " is a " + recorded + " nested session, so only " +
				"the " + recorded + " runner launcher can resume it");
		}
		BitSet taken = taken(slot.heldOut());
		synchronized (this)
		{
			boolean waitedForHolder = false;
			while (true)
			{
				if (taken.get(slot.number() - 1))
				{
					if (!stopping.contains(slot))
						throw new SessionRefusedException("Another launch is resuming the session " + sessionId);
					waitedForHolder = true;
				}
				else
				{
					if (waitedForHolder)
					{
						// The session that held the slot may have ended its nested session, which emptied the slot
						if (!keepsSession(slot, sessionId, harness))
							return Optional.empty();
						waitedForHolder = false;
					}
					if (taken.cardinality() < count)
						break;
				}
				wait(RELEASE_WAIT_MILLIS);
			}
			taken.set(slot.number() - 1);
		}
		boolean ready = false;
		try
		{
			Optional<ExclusiveFileLock> locked = SlotStartup.tryLock(fileLocks, slot.root());
			if (locked.isEmpty())
				throw new SessionRefusedException("A runner is still starting in the slot for session " + sessionId);
			try (ExclusiveFileLock _ = locked.get())
			{
				if (!keepsSession(slot, sessionId, harness))
					return Optional.empty();
				Optional<SlotStartup.Permit> permit = readPermit(slot);
				if (!startupSettled(permit) || !stopRecordedProcesses(manifests(slot.root()), STOP_WAIT_MILLIS))
				{
					throw new SessionRefusedException("A process that the nested session " + sessionId + " recorded is " +
						"still alive");
				}
				if (permit.isPresent() && permit.get().state() == SlotStartup.State.PENDING)
					SlotStartup.cancel(slot.root(), permit.get().generation());
			}
			ready = true;
			return Optional.of(slot);
		}
		finally
		{
			if (!ready)
				free(slot);
		}
	}

	/**
	 * Discards the retained nested session that records a harness session: waits until a session that holds its slot and
	 * is being stopped gives the slot back, stops each process that the nested session recorded, under the checks of its
	 * identity, and empties its slot.
	 *
	 * @param sessionId the session that the harness reported
	 * @return {@code true} if a retained nested session recorded the session and was discarded, or {@code false} if none
	 *   did, including when the session that held the nested session's slot ended the nested session
	 * @throws NullPointerException    if {@code sessionId} is null
	 * @throws SessionRefusedException if a launch that is not being stopped holds the nested session's slot, or a process
	 *                                 that the nested session recorded stays alive once it is asked to end
	 * @throws IOException             if no readable nested session records the session and a nested session's manifest
	 *                                 cannot be read, or the slot cannot be emptied
	 * @throws InterruptedException    if the thread is interrupted while it waits
	 */
	public boolean discard(String sessionId) throws IOException, InterruptedException
	{
		requireThat(sessionId, "sessionId").isNotNull();
		Optional<Retained> found = findRetained(sessionId);
		if (found.isEmpty())
			return false;
		Slot slot = found.get().slot();
		String harness = found.get().harness();
		BitSet taken = taken(slot.heldOut());
		synchronized (this)
		{
			boolean waitedForHolder = false;
			while (taken.get(slot.number() - 1))
			{
				if (!stopping.contains(slot))
					throw new SessionRefusedException("A launch is resuming the session " + sessionId);
				waitedForHolder = true;
				wait(RELEASE_WAIT_MILLIS);
			}
			// The session that held the slot may have ended its nested session, which emptied the slot
			if (waitedForHolder && !keepsSession(slot, sessionId, harness))
				return false;
			taken.set(slot.number() - 1);
		}
		try
		{
			Optional<ExclusiveFileLock> locked = SlotStartup.tryLock(fileLocks, slot.root());
			if (locked.isEmpty())
				throw new SessionRefusedException("A runner is still starting in the slot for session " + sessionId);
			try (ExclusiveFileLock _ = locked.get())
			{
				if (!keepsSession(slot, sessionId, harness))
					return false;
				List<RecordedNestedSession> records = manifests(slot.root());
				if (!startupSettled(readPermit(slot)) || !stopRecordedProcesses(records, STOP_WAIT_MILLIS))
				{
					throw new SessionRefusedException("A process that the nested session " + sessionId + " recorded is " +
						"still alive");
				}
				removeSlot(slot, records);
			}
			return true;
		}
		finally
		{
			free(slot);
		}
	}

	/**
	 * Indicates whether a retained slot's nested session records a harness session.
	 *
	 * @param sessionId the session that the harness reported
	 * @return {@code true} if a readable retained nested session records the session
	 * @throws NullPointerException if {@code sessionId} is null
	 */
	public boolean retains(String sessionId)
	{
		requireThat(sessionId, "sessionId").isNotNull();
		try
		{
			return findRetained(sessionId).isPresent();
		}
		catch (IOException _)
		{
			return false;
		}
	}

	/**
	 * Observes the slot of an original trial launch after the broker fences its archive and accepted work.
	 *
	 * @param requestId the original trial launch's archive identifier
	 * @return its settled absence, active ownership, or retained resumption state
	 * @throws NullPointerException if {@code requestId} is null
	 * @throws IOException          if ownership or process liveness cannot be established
	 */
	public RetainedTrialResponse inspectTrial(UUID requestId) throws IOException
	{
		requireThat(requestId, "requestId").isNotNull();
		return recoverTrial(RetainedTrialRequest.status(requestId));
	}

	/**
	 * Removes the slot of a fenced trial launch if it still has the observed generation and session.
	 *
	 * @param requestId the original trial launch's archive identifier
	 * @param expected  its observed generation and session, or empty if it never started
	 * @return the discard result, forcing a new observation if the slot now has a different identity
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if ownership or process liveness cannot be established or cleanup fails
	 */
	public RetainedTrialResponse discardTrial(UUID requestId, Optional<Identity> expected) throws IOException
	{
		requireThat(requestId, "requestId").isNotNull();
		requireThat(expected, "expected").isNotNull();
		return recoverTrial(RetainedTrialRequest.discard(requestId, expected));
	}

	/**
	 * Reserves the slot of a fenced trial launch only while its observed session, startup generation and entire
	 * completed turn prefix still match. The caller keeps the reservation through prefix copying and runner startup,
	 * and gives it back through {@link #release(Slot)} or {@link #retain(Slot)}.
	 *
	 * @param requestId      the original trial launch's archive identifier
	 * @param expected       the observed generation and harness session
	 * @param completedTurns the observed number of completed turns
	 * @return the slot, or empty when the claim no longer matches or cannot be reserved
	 * @throws NullPointerException     if any reference arguments are null
	 * @throws IllegalArgumentException if {@code completedTurns} is negative
	 * @throws IOException              if ownership or process liveness cannot be established
	 */
	public Optional<Slot> acquireRetained(UUID requestId, Identity expected, int completedTurns) throws IOException
	{
		requireThat(requestId, "requestId").isNotNull();
		requireThat(expected, "expected").isNotNull();
		requireThat(completedTurns, "completedTurns").isNotNegative();
		Optional<Slot> found = findBoundTrial(requestId);
		if (found.isEmpty())
			return Optional.empty();
		Slot slot = found.get();
		if (!reserve(slot, true))
			return Optional.empty();
		boolean ready = false;
		try
		{
			Optional<ExclusiveFileLock> locked = SlotStartup.tryLock(fileLocks, slot.root());
			if (locked.isEmpty())
				return Optional.empty();
			try (ExclusiveFileLock _ = locked.get())
			{
				Optional<BoundTrial> observed = readBoundTrial(slot, requestId);
				if (observed.isEmpty())
					return Optional.empty();
				BoundTrial trial = observed.get();
				if (!trial.identity().equals(Optional.of(expected)))
					return Optional.empty();
				if (!startupSettled(trial.permit()) || !checkRecordedProcesses(trial.records(), false) ||
					!trial.readyTurns().equals(OptionalInt.of(completedTurns)))
					return Optional.empty();
			}
			ready = true;
			return Optional.of(slot);
		}
		finally
		{
			if (!ready)
				free(slot);
		}
	}

	/**
	 * Publishes the successor's evidence from an acknowledged guarded suspension. The same slot must still hold
	 * the observed generation and each of the completed turns while CAT copies the prefix.
	 *
	 * @param request the exact predecessor claim
	 * @param directory the broker's response directory
	 * @return the successor checkpoint on success, or the reason that no evidence was copied
	 * @throws IOException if the slot or its evidence cannot be read or copied
	 */
	RetainedTrialResponse exportSuccessor(RetainedTrialRequest request, Path directory) throws IOException
	{
		UUID id = request.requestId();
		RetainedTrialResponse current = inspectTrial(id);
		if (current.status() != Status.READY)
			return current;

		Identity predecessor = request.identity().orElseThrow();
		Identity successor = current.identity().orElseThrow();
		int turns = current.completedTurns().orElseThrow();
		RetainedTrialResponse unsafe = new RetainedTrialResponse(id, Status.UNSAFE, Optional.empty(),
			OptionalInt.empty());
		if (turns != request.completedTurns().orElseThrow() + 1 ||
			!successor.session().equals(predecessor.session()) ||
			successor.slotGeneration().equals(predecessor.slotGeneration()))
			return unsafe;

		Optional<Slot> reserved = acquireRetained(id, successor, turns);
		if (reserved.isEmpty())
			return new RetainedTrialResponse(id, Status.BUSY, Optional.empty(), OptionalInt.empty());
		Slot slot = reserved.get();
		try
		{
			if (!RetainedTrialSuccessor.matches(JSON, slot.root(), request, successor))
				return unsafe;
			HarnessSession session = successor.session().orElseThrow();
			Path nested = slot.root().resolve(session.harness()).resolve(slot.slotId());
			RetainedTrialPrefix.publish(nested, directory, turns);
			return current;
		}
		finally
		{
			retain(slot);
		}
	}

	/**
	 * Inspects or discards a trial while reserving its slot and excluding a concurrent runner startup.
	 *
	 * @param request the fenced original request and the recovery operation
	 * @return the trial's recovery state
	 * @throws IOException if ownership or process liveness cannot be established or cleanup fails
	 */
	private RetainedTrialResponse recoverTrial(RetainedTrialRequest request) throws IOException
	{
		UUID requestId = request.requestId();
		Optional<Slot> found = findBoundTrial(requestId);
		if (found.isEmpty())
			return new RetainedTrialResponse(requestId, Status.ABSENT, Optional.empty(), OptionalInt.empty());
		Slot slot = found.get();
		if (!reserve(slot, false))
			return new RetainedTrialResponse(requestId, Status.BUSY, Optional.empty(), OptionalInt.empty());
		try
		{
			Optional<ExclusiveFileLock> locked = SlotStartup.tryLock(fileLocks, slot.root());
			if (locked.isEmpty())
				return new RetainedTrialResponse(requestId, Status.BUSY, Optional.empty(), OptionalInt.empty());
			try (ExclusiveFileLock _ = locked.get())
			{
				Optional<BoundTrial> observed = readBoundTrial(slot, requestId);
				if (observed.isEmpty())
					return new RetainedTrialResponse(requestId, Status.ABSENT, Optional.empty(), OptionalInt.empty());
				BoundTrial trial = observed.get();
				Optional<Identity> identity = trial.identity();
				if (request.operation() == RetainedTrialRequest.Operation.DISCARD)
				{
					if (!identity.equals(request.identity()))
						return new RetainedTrialResponse(requestId, Status.UNSAFE, Optional.empty(), OptionalInt.empty());
					if (isStuck(trial))
						return stuck(requestId, slot);
					if (!trialSettled(trial))
						return new RetainedTrialResponse(requestId, Status.BUSY, Optional.empty(), OptionalInt.empty());
					removeSlot(slot, trial.records());
					return new RetainedTrialResponse(requestId, Status.DISCARDED, Optional.empty(), OptionalInt.empty());
				}
				if (isStuck(trial))
					return stuck(requestId, slot);
				if (!trialSettled(trial))
					return new RetainedTrialResponse(requestId, Status.BUSY, Optional.empty(), OptionalInt.empty());
				OptionalInt turns = trial.readyTurns();
				if (turns.isPresent())
					return new RetainedTrialResponse(requestId, Status.READY, identity, turns);
				return new RetainedTrialResponse(requestId, Status.UNSAFE, identity, OptionalInt.empty());
			}
		}
		finally
		{
			free(slot);
		}
	}

	/**
	 * Returns the answer for a trial whose slot is stuck.
	 *
	 * @param requestId the original launch's archive identifier
	 * @param slot      the stuck slot
	 * @return the answer, which names the slot that CAT left untouched
	 */
	private static RetainedTrialResponse stuck(UUID requestId, Slot slot)
	{
		return new RetainedTrialResponse(requestId, Status.STUCK, Optional.empty(), OptionalInt.empty(),
			Optional.of(slot.root().toAbsolutePath()));
	}

	/**
	 * Indicates whether a bound trial's slot is stuck. Startup must be settled, so that no starter can still write to
	 * the slot.
	 *
	 * @param trial its verified state while startup is excluded
	 * @return {@code true} if the slot holds a launch that stays uncertain with its owner gone
	 * @throws IOException if startup or process liveness cannot be established
	 */
	private boolean isStuck(BoundTrial trial) throws IOException
	{
		if (trial.session().isEmpty() || !startupSettled(trial.permit()))
			return false;
		return isStuck(trial.session().get().manifest());
	}

	/**
	 * Indicates whether a slot is stuck: a nested session below it recorded that a harness child may exist and recorded
	 * neither the child's identity nor its absence, and the process that launched it is gone. Such a slot is never
	 * emptied, so that a process that may still run in it is never mistaken for one that does not.
	 *
	 * @param slot the slot
	 * @return {@code true} if the slot is stuck, or {@code false} if it is not, or its nested sessions cannot be read or
	 *   checked, which emptying the slot reports
	 */
	private boolean isStuck(Slot slot)
	{
		try
		{
			for (RecordedNestedSession record : manifests(slot.root()))
			{
				if (isStuck(NestedSessionManager.readManifest(record.manifest())))
					return true;
			}
		}
		catch (IOException _)
		{
			// Emptying the slot reports the reason
		}
		return false;
	}

	/**
	 * Indicates whether a nested session's launch stays uncertain with its launcher gone.
	 *
	 * @param manifest the nested session's manifest
	 * @return {@code true} if the manifest records a possible child without an identity, and its owner is neither this
	 *   process nor alive
	 * @throws IOException if the owner's liveness cannot be established
	 */
	private boolean isStuck(NestedSessionManager.Manifest manifest) throws IOException
	{
		if (manifest.harnessProcessState() != NestedSessionManager.HarnessProcessState.STARTING)
			return false;
		return manifest.ownerPid() != ProcessHandle.current().pid() &&
			!liveness.isAlive(manifest.ownerPid(), manifest.ownerStart());
	}

	/**
	 * Reserves a slot for an immediate inspection, discard or guarded launch.
	 *
	 * @param slot          the candidate slot
	 * @param needsCapacity whether this reservation starts a runner
	 * @return whether the slot is now reserved
	 */
	private boolean reserve(Slot slot, boolean needsCapacity)
	{
		BitSet taken = taken(slot.heldOut());
		synchronized (this)
		{
			boolean canReserve = !taken.get(slot.number() - 1) && (!needsCapacity || taken.cardinality() < count);
			if (canReserve)
				taken.set(slot.number() - 1);
			return canReserve;
		}
	}

	/**
	 * Finds the sole slot that records the original launch archive, independently of its harness or project.
	 *
	 * @param requestId the original launch's archive identifier
	 * @return the binding's slot, or empty if no slot records it
	 * @throws IOException if a binding cannot be read or more than one slot claims the request
	 */
	private Optional<Slot> findBoundTrial(UUID requestId) throws IOException
	{
		if (!slotDirectoryExists(base))
			return Optional.empty();
		Slot match = null;
		try (Stream<Path> entries = Files.list(base))
		{
			for (Path directory : (Iterable<Path>) entries::iterator)
			{
				Matcher name = SLOT_NAME.matcher(directory.getFileName().toString());
				if (!name.matches() || !slotDirectoryExists(directory))
					continue;
				Slot candidate = slot(name.group("part").equals(HELD_OUT), Integer.parseInt(name.group("number")));
				if (!keepsRequest(candidate, requestId))
					continue;
				if (match != null)
					throw new IOException("More than one slot records original launch request " + requestId);
				match = candidate;
			}
		}
		return Optional.ofNullable(match);
	}

	/**
	 * Checks a protected directory without confusing unreadable or malformed ownership data with absence.
	 *
	 * @param directory the expected directory
	 * @return whether the directory exists
	 * @throws IOException if the directory cannot be checked or the path is of a different type
	 */
	private static boolean slotDirectoryExists(Path directory) throws IOException
	{
		try
		{
			if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory())
				throw new IOException("Invalid session slot directory: " + directory);
			return true;
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
	}

	/**
	 * Rechecks the original archive binding and its one nested session with the slot reserved and startup excluded.
	 *
	 * @param slot      the reserved slot
	 * @param requestId the expected original launch archive
	 * @return the slot's verified state, or empty when the binding no longer matches
	 * @throws IOException if ownership is ambiguous or malformed
	 */
	private Optional<BoundTrial> readBoundTrial(Slot slot, UUID requestId) throws IOException
	{
		if (!slotDirectoryExists(slot.root()) || !keepsRequest(slot, requestId))
			return Optional.empty();
		Optional<SlotStartup.Permit> permit = readPermit(slot);
		List<RecordedNestedSession> records = manifests(slot.root());
		if (records.size() > 1)
			throw new IOException("More than one session occupies the slot for trial request " + requestId);
		if (records.isEmpty())
			return Optional.of(new BoundTrial(permit, Optional.empty()));
		RecordedNestedSession record = records.getFirst();
		if (!record.slotId().equals(slot.slotId()))
			throw new IOException("Unexpected session path in slot for trial request " + requestId);
		NestedSessionManager.Manifest manifest = readSlotManifest(record);
		if (permit.isEmpty())
			throw new IOException("Missing startup generation for trial request " + requestId);
		SlotStartup.Permit generation = permit.get();
		boolean wrongOwner = generation.state() == SlotStartup.State.CLAIMED &&
			(generation.runnerPid() != manifest.ownerPid() ||
			!generation.runnerStart().equals(manifest.ownerStart()));
		if (wrongOwner)
			throw new IOException("The runner's manifest disagrees with the startup generation for trial " + requestId);
		return Optional.of(new BoundTrial(permit, Optional.of(new TrialSession(record, manifest))));
	}

	/**
	 * Checks a slot's original launch archive record without interpreting the current harness session.
	 *
	 * @param slot      the candidate slot
	 * @param requestId the exact original archive identifier
	 * @return whether the slot records that identifier
	 * @throws IOException if the record exists and is invalid or unreadable
	 */
	private static boolean keepsRequest(Slot slot, UUID requestId) throws IOException
	{
		Path file = slot.root().resolve(SessionRequestBroker.ORIGINAL_REQUEST_FILE);
		try
		{
			if (!Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
				throw new IOException("Original launch request record is not a regular file: " + file);
			String raw = BoundedInput.readUtf8(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), file,
				MAXIMUM_BINDING_BYTES, "Original launch request");
			JsonNode binding = JSON.readTree(raw);
			if (binding == null || !binding.isObject() || binding.size() != 2)
				throw new IOException("Invalid original launch request record: " + file);
			JsonNode version = binding.path("format_version");
			String id = binding.path("request_id").stringValue("");
			if (!version.isInt() || version.intValue() != 1)
				throw new IOException("Invalid original launch request record: " + file);
			SessionRequestCli.validateId(id);
			return requestId.toString().equals(id);
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
		catch (IllegalArgumentException | JacksonException e)
		{
			throw new IOException("Invalid original launch request record: " + file, e);
		}
	}

	/**
	 * Checks that the slot's starter and any nested session processes have settled. An orphaned runner can be stopped
	 * when the startup record and manifest agree on its identity. Its old harness is stopped after the runner exits.
	 *
	 * @param trial its verified state while startup is excluded
	 * @return whether no process remains that can change the slot
	 * @throws IOException if liveness or process identity cannot be established
	 */
	private boolean trialSettled(BoundTrial trial) throws IOException
	{
		if (startupSettled(trial.permit()))
			return stopRecordedProcesses(trial.records());
		SlotStartup.Permit permit = trial.permit().orElseThrow();
		if (permit.state() == SlotStartup.State.CLAIMED && trial.session().isPresent() &&
			permit.runnerPid() != ProcessHandle.current().pid() &&
			!liveness.isAlive(permit.brokerPid(), permit.brokerStart()))
		{
			liveness.terminate(permit.runnerPid(), permit.runnerStart());
			// An exited runner may have written new evidence after this probe read its manifest.
		}
		return false;
	}

	/**
	 * Reads a slot's startup record while holding its stable lock.
	 *
	 * @param slot the reserved slot
	 * @return its startup record, or empty when no generation has been issued
	 * @throws IOException if the record is unreadable or invalid
	 */
	private static Optional<SlotStartup.Permit> readPermit(Slot slot) throws IOException
	{
		try
		{
			return Optional.of(SlotStartup.read(slot.root()));
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
	}

	/**
	 * Checks whether the startup record still authorizes a child to start or names a live child.
	 *
	 * @param permit the startup record read under the stable lock
	 * @return whether startup cannot write to the slot
	 * @throws IOException if liveness cannot be checked
	 */
	private boolean startupSettled(Optional<SlotStartup.Permit> permit) throws IOException
	{
		if (permit.isEmpty())
			return true;
		SlotStartup.Permit record = permit.get();
		return switch (record.state())
		{
			case PENDING -> !liveness.isAlive(record.brokerPid(), record.brokerStart());
			case CLAIMED -> !liveness.isAlive(record.runnerPid(), record.runnerStart());
			case CANCELLED -> true;
		};
	}

	/**
	 * Rechecks the original native identity after waiting for a public resume or discard reservation.
	 *
	 * @param slot      the candidate slot
	 * @param sessionId the original harness session
	 * @param harness   the original harness
	 * @return whether the slot still retains that session
	 * @throws IOException if the manifest cannot be read or checked
	 */
	private static boolean keepsSession(Slot slot, String sessionId, String harness) throws IOException
	{
		for (RecordedNestedSession record : manifests(slot.root()))
		{
			if (!record.harness().equals(harness) || !record.slotId().equals(slot.slotId()))
				continue;
			NestedSessionManager.Manifest manifest = readSlotManifest(record);
			if (isRetained(manifest) && sessionId.equals(manifest.harnessSessionId()))
				return true;
		}
		return false;
	}

	/**
	 * Finds the retained slot whose nested session records a harness session.
	 *
	 * @param sessionId the session
	 * @return the slot and its nested session's harness, or none if no retained slot records the session
	 * @throws IOException if no readable nested session records the session and a nested session's manifest cannot be
	 *                     read
	 */
	private Optional<Retained> findRetained(String sessionId) throws IOException
	{
		if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS))
			return Optional.empty();
		IOException unreadable = null;
		List<Path> directories;
		try (Stream<Path> entries = Files.list(base))
		{
			directories = entries.sorted().toList();
		}
		for (Path directory : directories)
		{
			Matcher name = SLOT_NAME.matcher(directory.getFileName().toString());
			if (!name.matches() || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
				continue;
			Slot slot = slot(name.group("part").equals(HELD_OUT), Integer.parseInt(name.group("number")));
			try
			{
				for (RecordedNestedSession record : manifests(slot.root()))
				{
					NestedSessionManager.Manifest manifest = readSlotManifest(record);
					if (isRetained(manifest) && sessionId.equals(manifest.harnessSessionId()))
						return Optional.of(new Retained(slot, manifest.harness()));
				}
			}
			catch (IOException e)
			{
				if (unreadable == null)
					unreadable = e;
				else
					unreadable.addSuppressed(e);
			}
		}
		if (unreadable != null)
			throw unreadable;
		return Optional.empty();
	}

	/**
	 * Indicates whether a slot is retained: a nested session below it records its harness session and no end, so that the
	 * nested session's harness session can continue.
	 *
	 * @param slot the slot
	 * @return {@code true} if the slot is retained, or {@code false} if it is not, or its nested sessions cannot be read,
	 *   which emptying the slot reports
	 * @throws NullPointerException if {@code slot} is null
	 */
	public static boolean isRetained(Slot slot)
	{
		requireThat(slot, "slot").isNotNull();
		try
		{
			for (RecordedNestedSession record : manifests(slot.root()))
			{
				if (isRetained(NestedSessionManager.readManifest(record.manifest())))
					return true;
			}
		}
		catch (IOException _)
		{
			// Emptying the slot reports the reason
		}
		return false;
	}

	/**
	 * Returns the length of the leading part of a path that names a slot's nested session folder. A harness reports the
	 * paths of the session's own files, so the part tells which slot ran the session, which sessions of different slots
	 * do not share, and says nothing about what the session loaded.
	 *
	 * @param path the path, written with {@code /} or {@code \} between its names
	 * @return the number of characters of {@code path} that name the nested session's folder, or empty if the path does
	 *   not lie in a slot's nested session. When nested sessions of slots lie inside one another, the innermost wins.
	 * @throws NullPointerException if {@code path} is null
	 */
	public static OptionalInt nestedSessionLength(String path)
	{
		requireThat(path, "path").isNotNull();
		Matcher matcher = NESTED_SESSION_IN_SLOT.matcher(path);
		if (!matcher.find())
			return OptionalInt.empty();
		return OptionalInt.of(matcher.end());
	}

	/**
	 * Indicates whether a nested session can continue: it records its harness session and no end.
	 *
	 * @param manifest the nested session's manifest
	 * @return {@code true} if the nested session can continue
	 */
	private static boolean isRetained(NestedSessionManager.Manifest manifest)
	{
		return manifest.harnessSessionId() != null && manifest.state().equals("running");
	}

	/**
	 * Asks every process that the manifests of a slot's nested sessions record to end, and waits a while for them to.
	 *
	 * @param records the nested sessions of the slot
	 * @param millis  how long to wait, in milliseconds
	 * @return {@code true} if no recorded process remains alive
	 * @throws IOException          if a nested session's manifest cannot be read or does not match its nested session
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	private boolean stopRecordedProcesses(List<RecordedNestedSession> records, long millis)
		throws IOException, InterruptedException
	{
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		while (!stopRecordedProcesses(records))
		{
			if (System.nanoTime() - deadline >= 0)
				return false;
			synchronized (this)
			{
				wait(PROCESS_WAIT_MILLIS);
			}
		}
		return true;
	}

	/**
	 * Returns the slots that a part holds.
	 *
	 * @param heldOut {@code true} for the held-out part
	 * @return the slots that are taken
	 */
	private BitSet taken(boolean heldOut)
	{
		if (heldOut)
			return heldOutTaken;
		return developmentTaken;
	}

	/**
	 * Returns a slot.
	 *
	 * @param heldOut {@code true} for the held-out part
	 * @param number  the slot's number within its part
	 * @return the slot
	 */
	private Slot slot(boolean heldOut, int number)
	{
		String part = DEVELOPMENT;
		if (heldOut)
			part = HELD_OUT;
		return new Slot(base.resolve(part + number), SLOT_ID + number, heldOut, number);
	}

	/**
	 * Marks a slot as free and wakes the requests that wait for one.
	 *
	 * @param slot the slot
	 */
	private void free(Slot slot)
	{
		synchronized (this)
		{
			taken(slot.heldOut()).clear(slot.number() - 1);
			stopping.remove(slot);
			notifyAll();
		}
	}

	/**
	 * Stops the processes that the slot's previous session recorded, and if none remains alive, removes the slot's
	 * directory. Each nested session's manifest is removed after everything else in the nested session, so that a removal
	 * that stops partway leaves the record of the nested session's processes for the next attempt. A stuck slot is left
	 * as it is, and nothing in it is signalled.
	 *
	 * @param slot the slot
	 * @return {@code true} if the slot has no directory, or {@code false} if a recorded process remains alive or the slot
	 *   is stuck
	 * @throws IOException if the slot cannot be emptied
	 */
	private boolean empty(Slot slot) throws IOException
	{
		Optional<ExclusiveFileLock> locked = SlotStartup.tryLock(fileLocks, slot.root());
		if (locked.isEmpty())
			return false;
		try (ExclusiveFileLock _ = locked.get())
		{
			List<RecordedNestedSession> records = manifests(slot.root());
			if (!startupSettled(readPermit(slot)) || isStuck(records) || !stopRecordedProcesses(records))
				return false;
			removeSlot(slot, records);
			return true;
		}
	}

	/**
	 * Indicates whether any of a slot's nested sessions is stuck, which keeps the slot from being emptied or signalled.
	 *
	 * @param records the nested sessions of the slot
	 * @return {@code true} if a nested session's launch stays uncertain with its launcher gone
	 * @throws IOException if a nested session's manifest cannot be read or does not match its nested session
	 */
	private boolean isStuck(List<RecordedNestedSession> records) throws IOException
	{
		for (RecordedNestedSession record : records)
		{
			if (isStuck(readSlotManifest(record)))
				return true;
		}
		return false;
	}

	/**
	 * Removes the settled session and its ownership records while holding the stable lock outside the slot.
	 *
	 * @param slot    the slot to remove
	 * @param records the nested sessions whose manifests are removed after their other files
	 * @throws IOException if the slot cannot be removed
	 */
	private static void removeSlot(Slot slot, List<RecordedNestedSession> records) throws IOException
	{
		for (RecordedNestedSession record : records)
			NestedSessionManager.deleteSessionFolder(record.manifest().getParent());
		TemporaryDirectories.deleteRecursively(slot.root());
	}

	/**
	 * Asks every process that the manifests of the slot's nested sessions record to end, and checks whether any remains.
	 *
	 * @param records the nested sessions of the slot
	 * @return {@code true} if no recorded process remains alive
	 * @throws IOException if a nested session's manifest cannot be read or does not match its nested session
	 */
	private boolean stopRecordedProcesses(List<RecordedNestedSession> records) throws IOException
	{
		return checkRecordedProcesses(records, true);
	}

	/**
	 * Checks recorded processes and optionally stops a native process whose runner has already exited.
	 *
	 * @param records     the nested sessions of the slot
	 * @param stopOrphans whether to stop recorded native processes after verifying their runner has exited
	 * @return {@code true} if no recorded process remains alive
	 * @throws IOException if a nested session's manifest cannot be read or does not match its nested session
	 */
	private boolean checkRecordedProcesses(List<RecordedNestedSession> records, boolean stopOrphans) throws IOException
	{
		List<ValidatedNestedSession> validated = new ArrayList<>();
		for (RecordedNestedSession record : records)
		{
			NestedSessionManager.Manifest manifest = readSlotManifest(record);
			if (manifest.harnessProcessState() == NestedSessionManager.HarnessProcessState.STARTING)
				throw new IOException("Harness launch state is unknown; refusing to empty slot: " + record.manifest());
			boolean currentOwner = manifest.ownerPid() == ProcessHandle.current().pid();
			boolean ownerAlive = currentOwner || liveness.isAlive(manifest.ownerPid(), manifest.ownerStart());
			boolean childAlive = false;
			if (manifest.harnessProcessState() == NestedSessionManager.HarnessProcessState.RECORDED)
				childAlive = liveness.isAlive(manifest.harnessPid(), manifest.harnessStart());
			validated.add(new ValidatedNestedSession(manifest, currentOwner, ownerAlive, childAlive));
		}
		boolean ended = true;
		for (ValidatedNestedSession nestedSession : validated)
		{
			if (nestedSession.ownerAlive() && !nestedSession.currentOwner())
			{
				ended = false;
				continue;
			}
			if (!nestedSession.childAlive())
				continue;
			long pid = nestedSession.manifest().harnessPid();
			if (!stopOrphans || pid == ProcessHandle.current().pid())
			{
				ended = false;
				continue;
			}
			liveness.terminate(pid, nestedSession.manifest().harnessStart());
			if (liveness.isAlive(pid, nestedSession.manifest().harnessStart()))
				ended = false;
		}
		return ended;
	}

	/**
	 * Reads a manifest and verifies its path-derived harness and slot identifier.
	 *
	 * @param record the manifest and its expected identity
	 * @return the manifest with matching identity
	 * @throws IOException if the manifest is unreadable or does not match its path
	 */
	private static NestedSessionManager.Manifest readSlotManifest(RecordedNestedSession record) throws IOException
	{
		NestedSessionManager.Manifest manifest = NestedSessionManager.readManifest(record.manifest());
		if (!manifest.harness().equals(record.harness()) || !manifest.id().equals(record.slotId()))
			throw new IOException("Session manifest does not match its slot path: " + record.manifest());
		return manifest;
	}

	/**
	 * Returns the manifests of the nested sessions below a slot, which are at {@code <harness>/<id>/session.json}. A
	 * session folder without a manifest records no process and is left out.
	 *
	 * @param root the slot's directory
	 * @return the manifests
	 * @throws IOException if the slot's directories cannot be read, or a nested session's manifest is not a regular file
	 */
	private static List<RecordedNestedSession> manifests(Path root) throws IOException
	{
		List<RecordedNestedSession> manifests = new ArrayList<>();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			return manifests;
		try (Stream<Path> harnesses = Files.list(root))
		{
			for (Path harness : harnesses.toList())
			{
				if (!Files.isDirectory(harness, LinkOption.NOFOLLOW_LINKS))
					continue;
				try (Stream<Path> sessionFolders = Files.list(harness))
				{
					for (Path sessionFolder : sessionFolders.toList())
					{
						if (!Files.isDirectory(sessionFolder, LinkOption.NOFOLLOW_LINKS) ||
							!sessionFolder.getFileName().toString().startsWith(SLOT_ID))
							continue;
						Path manifest = sessionFolder.resolve("session.json");
						// A session folder without a manifest records no process; emptying the slot removes it
						if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS))
							continue;
						if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
							throw new IOException("Slot session manifest is not a regular file: " + manifest);
						manifests.add(new RecordedNestedSession(harness.getFileName().toString(),
							sessionFolder.getFileName().toString(), manifest));
					}
				}
			}
		}
		return manifests;
	}

	/**
	 * A retained slot and the harness of its nested session.
	 *
	 * @param slot    the slot
	 * @param harness the nested session's harness
	 */
	private record Retained(Slot slot, String harness)
	{
	}

	/**
	 * A path-derived nested session whose manifest is checked before slot reuse.
	 *
	 * @param harness the harness directory name
	 * @param slotId the generated slot ID
	 * @param manifest the expected manifest path
	 */
	private record RecordedNestedSession(String harness, String slotId, Path manifest)
	{
	}

	/**
	 * A parsed nested session whose identities have all been checked before cleanup starts.
	 *
	 * @param manifest the parsed manifest
	 * @param currentOwner whether the owner is this JVM
	 * @param ownerAlive whether the owner is still live
	 * @param childAlive whether the recorded harness process is still live
	 */
	private record ValidatedNestedSession(NestedSessionManager.Manifest manifest, boolean currentOwner,
		boolean ownerAlive, boolean childAlive)
	{
	}

	/**
	 * A parsed manifest and its location inside a bound trial's slot.
	 *
	 * @param record   the nested session's path and expected identity
	 * @param manifest the verified manifest
	 */
	private record TrialSession(RecordedNestedSession record, NestedSessionManager.Manifest manifest)
	{
	}

	/**
	 * A trial slot's generation and nested session, read while the slot is reserved and startup is excluded.
	 *
	 * @param permit  the current startup record
	 * @param session the slot's recorded session
	 */
	private record BoundTrial(Optional<SlotStartup.Permit> permit, Optional<TrialSession> session)
	{
		/**
		 * Returns the generation and known native identity for comparison by a later request.
		 *
		 * @return the exact generation, with a harness session if the manifest names one
		 */
		Optional<Identity> identity()
		{
			Optional<HarnessSession> nativeSession = session.map(TrialSession::manifest).
				filter(manifest -> manifest.harnessSessionId() != null).
				map(manifest -> new HarnessSession(manifest.harness(), manifest.harnessSessionId()));
			return permit.map(value -> new Identity(UUID.fromString(value.generation()), nativeSession));
		}

		/**
		 * Returns a ready turn count only if the runner claimed the generation, no manual resume has occurred, and all
		 * required prefix artifacts match the manifest's turn count.
		 *
		 * @return the verified completed turn count, or empty when it cannot be recovered
		 * @throws IOException if the prefix artifacts cannot be read
		 */
		OptionalInt readyTurns() throws IOException
		{
			if (permit.isEmpty() || permit.get().state() != SlotStartup.State.CLAIMED ||
				permit.get().manualResume() || session.isEmpty())
				return OptionalInt.empty();
			TrialSession trial = session.get();
			int completedTurns = trial.manifest().turnNumber();
			if (!isRetained(trial.manifest()) || !RetainedTrialPrefix.validates(trial.record().manifest().getParent(),
				completedTurns))
				return OptionalInt.empty();
			return OptionalInt.of(completedTurns);
		}

		/**
		 * Returns the trial's known session for process settlement and cleanup.
		 *
		 * @return the zero or one nested session in this slot
		 */
		List<RecordedNestedSession> records()
		{
			return session.map(TrialSession::record).stream().toList();
		}
	}
}
