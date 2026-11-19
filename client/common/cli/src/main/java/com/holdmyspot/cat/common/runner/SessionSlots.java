package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A fixed set of run directories, one for each requested session that may run at once, which a requested session
 * takes for the whole of its run. Every session builds its run in a slot at the same path as the session before it,
 * so the paths of its home, harness configuration directory and project repeat from one session to the next, while
 * no file of the earlier session remains.
 * <p>
 * A slot belongs to either the development part or the held-out part. The two parts have slots of their own, in
 * separate directories, so that a session of one part never runs where a session of the other did.
 * <p>
 * The paths are short: {@code d1}, {@code d2}, and so on, for the development part's slots and {@code h1},
 * {@code h2}, and so on, for the held-out part's, each run below its harness's name under the identifier
 * {@code s-<number>}. Every path of a session's home and project starts with its run's path, and a harness repeats
 * those paths many times in what it sends the model, in its sandbox description among others, and again for each
 * level of requested sessions. The names stay distinct across parts and numbers, and none is a name that Windows
 * reserves.
 * <p>
 * A slot is emptied before a session takes it, and again once the session has ended, but a slot is never emptied or
 * handed on while a process that its previous session recorded in its run's manifest remains alive: CAT stops each such
 * process, and if one stays alive, the slot waits. The manifest records only the runner's process and the harness
 * process, not the processes below them. A session folder without a manifest records no process, so emptying the slot
 * removes it without stopping anything; a manifest that cannot be read or is malformed keeps the slot from being
 * emptied.
 * <p>
 * A slot whose run records the session that its harness reported, and no end, is <b>retained</b>: its session was
 * interrupted and may continue, so no other session takes the slot, and a session that needs a slot of the part takes
 * the next slot instead, numbered past the slots' count if need be. No more sessions of a part run at once than the
 * count allows. A retained slot is given only to the launch that resumes its run's session, and is emptied only when
 * that launch ends its run or the run is discarded.
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
	 * The start of the identifier of each slot's run, which the slot's number follows.
	 */
	private static final String RUN_ID = "s-";
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
	 * How long a resume or a discard waits for the processes that a retained run recorded to end once they are asked
	 * to, in milliseconds.
	 */
	private static final long STOP_WAIT_MILLIS = 1_000;
	/**
	 * The name of a slot's directory below the slots' base.
	 */
	private static final Pattern SLOT_NAME = Pattern.compile("(?<part>[" + DEVELOPMENT + HELD_OUT +
		"])(?<number>[1-9][0-9]{0,8})");
	private final Path base;
	private final int count;
	private final HarnessProcessLiveness liveness;
	private final BitSet developmentTaken;
	private final BitSet heldOutTaken;

	/**
	 * A slot that a session holds.
	 *
	 * @param root    the directory that the session uses as its results folder, which holds its run below the harness's
	 *                name
	 * @param runId   the identifier of the session's run, which is the same for every session of the slot
	 * @param heldOut {@code true} if the slot belongs to the held-out part
	 * @param number  the slot's number within its part, starting at {@code 1}
	 */
	public record Slot(Path root, String runId, boolean heldOut, int number)
	{
		/**
		 * Creates a slot.
		 *
		 * @param root    the directory that the session uses as its results folder
		 * @param runId   the identifier of the session's run
		 * @param heldOut {@code true} if the slot belongs to the held-out part
		 * @param number  the slot's number within its part
		 * @throws NullPointerException     if {@code root} or {@code runId} are null
		 * @throws IllegalArgumentException if {@code number} is not positive
		 */
		public Slot
		{
			requireThat(root, "root").isNotNull();
			requireThat(runId, "runId").isNotNull();
			requireThat(number, "number").isPositive();
		}
	}

	/**
	 * Creates the slots of a server or a run, which exist as directories only while sessions hold them.
	 *
	 * @param base     the absolute directory below which the slots live
	 * @param count    how many sessions of each part may run at once
	 * @param liveness checks and stops the processes that a session's run records
	 * @throws NullPointerException     if {@code base} or {@code liveness} are null
	 * @throws IllegalArgumentException if {@code base} is relative or {@code count} is not positive
	 */
	public SessionSlots(Path base, int count, HarnessProcessLiveness liveness)
	{
		requireThat(base, "base").isNotNull().isAbsolute();
		requireThat(count, "count").isPositive();
		requireThat(liveness, "liveness").isNotNull();
		this.base = base;
		this.count = count;
		this.liveness = liveness;
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
		BitSet taken = taken(heldOut);
		while (true)
		{
			Slot slot;
			synchronized (this)
			{
				while (taken.cardinality() >= count)
					wait(RELEASE_WAIT_MILLIS);
				int index = taken.nextClearBit(0);
				while (isRetained(slot(heldOut, index + 1)))
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
	 * Gives a slot back without emptying it, once its session was interrupted. The slot stays retained while its run
	 * records its harness session and no end; a slot whose run does not is emptied by the next session to take it.
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
	 * Takes the retained slot whose run records a harness session, so that a launch continues the session there. Waits
	 * until fewer sessions of the slot's part run than the slots' count allows, and stops each process that the run
	 * recorded, under the checks of its identity. The slot keeps its files, and stays the caller's until it is
	 * {@link #release(Slot) released} or {@link #retain(Slot) retained}.
	 *
	 * @param sessionId the session that the harness reported
	 * @param harness   the harness of the launch that resumes the session
	 * @return the slot, or none if no retained slot records the session
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the slot's run is of another harness, a launch holds the slot, or a process
	 *                                 that the run recorded stays alive once it is asked to end
	 * @throws IOException             if no readable run records the session and a run's manifest cannot be read, or
	 *                                 the slot's run cannot be checked
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
			throw new SessionRefusedException("The session " + sessionId + " is of a " + recorded + " run, so only " +
				"the " + recorded + " runner launcher can resume it");
		}
		BitSet taken = taken(slot.heldOut());
		synchronized (this)
		{
			while (true)
			{
				if (taken.get(slot.number() - 1))
					throw new SessionRefusedException("Another launch is resuming the session " + sessionId);
				if (taken.cardinality() < count)
					break;
				wait(RELEASE_WAIT_MILLIS);
			}
			taken.set(slot.number() - 1);
		}
		boolean ready = false;
		try
		{
			if (!stopRecordedProcesses(manifests(slot.root()), STOP_WAIT_MILLIS))
			{
				throw new SessionRefusedException("A process that the run of session " + sessionId + " recorded is " +
					"still alive");
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
	 * Discards the retained run that records a harness session: stops each process that the run recorded, under the
	 * checks of its identity, and empties its slot.
	 *
	 * @param sessionId the session that the harness reported
	 * @return {@code true} if a retained run recorded the session and was discarded, or {@code false} if none did
	 * @throws NullPointerException    if {@code sessionId} is null
	 * @throws SessionRefusedException if a launch is resuming the run, or a process that the run recorded stays alive
	 *                                 once it is asked to end
	 * @throws IOException             if no readable run records the session and a run's manifest cannot be read, or
	 *                                 the slot cannot be emptied
	 * @throws InterruptedException    if the thread is interrupted while it waits
	 */
	public boolean discard(String sessionId) throws IOException, InterruptedException
	{
		requireThat(sessionId, "sessionId").isNotNull();
		Optional<Retained> found = findRetained(sessionId);
		if (found.isEmpty())
			return false;
		Slot slot = found.get().slot();
		BitSet taken = taken(slot.heldOut());
		synchronized (this)
		{
			if (taken.get(slot.number() - 1))
				throw new SessionRefusedException("A launch is resuming the session " + sessionId);
			taken.set(slot.number() - 1);
		}
		try
		{
			if (!stopRecordedProcesses(manifests(slot.root()), STOP_WAIT_MILLIS) || !empty(slot))
			{
				throw new SessionRefusedException("A process that the run of session " + sessionId + " recorded is " +
					"still alive");
			}
			return true;
		}
		finally
		{
			free(slot);
		}
	}

	/**
	 * Indicates whether a retained slot's run records a harness session.
	 *
	 * @param sessionId the session that the harness reported
	 * @return {@code true} if a readable retained run records the session
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
	 * Finds the retained slot whose run records a harness session.
	 *
	 * @param sessionId the session
	 * @return the slot and its run's harness, or none if no retained slot records the session
	 * @throws IOException if no readable run records the session and a run's manifest cannot be read
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
				for (RecordedRun record : manifests(slot.root()))
				{
					HarnessRunManager.Manifest manifest = HarnessRunManager.readManifest(record.manifest());
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
	 * Indicates whether a slot is retained: a run below it records its harness session and no end, so that the run's
	 * session can continue.
	 *
	 * @param slot the slot
	 * @return {@code true} if the slot is retained, or {@code false} if it is not, or its runs cannot be read, which
	 *   emptying the slot reports
	 * @throws NullPointerException if {@code slot} is null
	 */
	public static boolean isRetained(Slot slot)
	{
		requireThat(slot, "slot").isNotNull();
		try
		{
			for (RecordedRun record : manifests(slot.root()))
			{
				if (isRetained(HarnessRunManager.readManifest(record.manifest())))
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
	 * Indicates whether a run can continue: it records its harness session and no end.
	 *
	 * @param manifest the run's manifest
	 * @return {@code true} if the run can continue
	 */
	private static boolean isRetained(HarnessRunManager.Manifest manifest)
	{
		return manifest.harnessSessionId() != null && manifest.state().equals("running");
	}

	/**
	 * Asks every process that the manifests of a slot's runs record to end, and waits a while for them to.
	 *
	 * @param records the runs of the slot
	 * @param millis  how long to wait, in milliseconds
	 * @return {@code true} if no recorded process remains alive
	 * @throws IOException          if a run's manifest cannot be read or does not match its run
	 * @throws InterruptedException if the thread is interrupted while it waits
	 */
	private boolean stopRecordedProcesses(List<RecordedRun> records, long millis)
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
		return new Slot(base.resolve(part + number), RUN_ID + number, heldOut, number);
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
			notifyAll();
		}
	}

	/**
	 * Stops the processes that the slot's previous session recorded, and if none remains alive, removes the slot's
	 * directory. Each run's manifest is removed after everything else in the run, so that a removal that stops partway
	 * leaves the record of the run's processes for the next attempt.
	 *
	 * @param slot the slot
	 * @return {@code true} if the slot has no directory, or {@code false} if a recorded process remains alive
	 * @throws IOException if the slot cannot be emptied
	 */
	private boolean empty(Slot slot) throws IOException
	{
		List<RecordedRun> records = manifests(slot.root());
		if (!stopRecordedProcesses(records))
			return false;
		for (RecordedRun record : records)
			HarnessRunManager.deleteRunDirectory(record.manifest().getParent());
		TemporaryDirectories.deleteRecursively(slot.root());
		return true;
	}

	/**
	 * Asks every process that the manifests of the slot's runs record to end, and checks whether any remains.
	 *
	 * @param records the runs of the slot
	 * @return {@code true} if no recorded process remains alive
	 * @throws IOException if a run's manifest cannot be read or does not match its run
	 */
	private boolean stopRecordedProcesses(List<RecordedRun> records) throws IOException
	{
		List<ValidatedRun> validated = new ArrayList<>();
		for (RecordedRun record : records)
		{
			HarnessRunManager.Manifest manifest = HarnessRunManager.readManifest(record.manifest());
			if (!manifest.harness().equals(record.harness()) || !manifest.id().equals(record.runId()))
				throw new IOException("Run manifest does not match its slot path: " + record.manifest());
			if (manifest.harnessProcessState() == HarnessRunManager.HarnessProcessState.STARTING)
				throw new IOException("Harness launch state is unknown; refusing to empty slot: " + record.manifest());
			boolean currentOwner = manifest.ownerPid() == ProcessHandle.current().pid();
			boolean ownerAlive = currentOwner || liveness.isAlive(manifest.ownerPid(), manifest.ownerStart());
			boolean childAlive = false;
			if (manifest.harnessProcessState() == HarnessRunManager.HarnessProcessState.RECORDED)
				childAlive = liveness.isAlive(manifest.harnessPid(), manifest.harnessStart());
			validated.add(new ValidatedRun(manifest, currentOwner, ownerAlive, childAlive));
		}
		boolean ended = true;
		for (ValidatedRun run : validated)
		{
			if (run.ownerAlive() && !run.currentOwner())
			{
				ended = false;
				continue;
			}
			if (!run.childAlive())
				continue;
			long pid = run.manifest().harnessPid();
			if (pid == ProcessHandle.current().pid())
			{
				ended = false;
				continue;
			}
			liveness.terminate(pid, run.manifest().harnessStart());
			if (liveness.isAlive(pid, run.manifest().harnessStart()))
				ended = false;
		}
		return ended;
	}

	/**
	 * Returns the manifests of the runs below a slot, which are at {@code <harness>/<run>/session.json}. A session folder
	 * without a manifest records no process and is left out.
	 *
	 * @param root the slot's directory
	 * @return the manifests
	 * @throws IOException if the slot's directories cannot be read, or a run's manifest is not a regular file
	 */
	private static List<RecordedRun> manifests(Path root) throws IOException
	{
		List<RecordedRun> manifests = new ArrayList<>();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			return manifests;
		try (Stream<Path> harnesses = Files.list(root))
		{
			for (Path harness : harnesses.toList())
			{
				if (!Files.isDirectory(harness, LinkOption.NOFOLLOW_LINKS))
					continue;
				try (Stream<Path> runs = Files.list(harness))
				{
					for (Path run : runs.toList())
					{
						if (!Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS) ||
							!run.getFileName().toString().startsWith(RUN_ID))
							continue;
						Path manifest = run.resolve("session.json");
						// A session folder without a manifest records no process; emptying the slot removes it
						if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS))
							continue;
						if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
							throw new IOException("Slot run manifest is not a regular file: " + manifest);
						manifests.add(new RecordedRun(harness.getFileName().toString(),
							run.getFileName().toString(), manifest));
					}
				}
			}
		}
		return manifests;
	}

	/**
	 * A retained slot and the harness of its run.
	 *
	 * @param slot    the slot
	 * @param harness the run's harness
	 */
	private record Retained(Slot slot, String harness)
	{
	}

	/**
	 * A path-derived run whose manifest is checked before slot reuse.
	 *
	 * @param harness the harness directory name
	 * @param runId the generated run identifier
	 * @param manifest the expected manifest path
	 */
	private record RecordedRun(String harness, String runId, Path manifest)
	{
	}

	/**
	 * A parsed run whose identities have all been checked before cleanup starts.
	 *
	 * @param manifest the parsed manifest
	 * @param currentOwner whether the owner is this JVM
	 * @param ownerAlive whether the owner is still live
	 * @param childAlive whether the recorded harness process is still live
	 */
	private record ValidatedRun(HarnessRunManager.Manifest manifest, boolean currentOwner,
		boolean ownerAlive, boolean childAlive)
	{
	}
}
