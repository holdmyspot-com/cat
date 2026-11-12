package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 * handed on while a process that its previous session recorded in its run's manifest remains alive: CAT stops each
 * such process, and if one stays alive, the slot waits. The manifest records only the runner's process and the
 * harness process, not the processes below them.
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
	private final Path base;
	private final int count;
	private final HarnessProcessLiveness liveness;
	private final JsonMapper json = JsonMapper.builder().build();
	private final BitSet developmentTaken;
	private final BitSet heldOutTaken;

	/**
	 * A slot that a session holds.
	 *
	 * @param root    the directory that the session uses as its run root, which holds its run below the harness's
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
		 * @param root    the directory that the session uses as its run root
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
	 * Waits for a free slot of a part whose previous session has ended, empties it and gives it to the caller. The
	 * slot stays the caller's until it is {@link #release(Slot) released}.
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
				int index = taken.nextClearBit(0);
				while (index >= count)
				{
					wait(RELEASE_WAIT_MILLIS);
					index = taken.nextClearBit(0);
				}
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
	 * directory.
	 *
	 * @param slot the slot
	 * @return {@code true} if the slot has no directory, or {@code false} if a recorded process remains alive
	 * @throws IOException if the slot cannot be emptied
	 */
	private boolean empty(Slot slot) throws IOException
	{
		if (!stopRecordedProcesses(slot.root()))
			return false;
		TemporaryDirectories.deleteRecursively(slot.root());
		return true;
	}

	/**
	 * Asks every process that the manifests of the slot's runs record to end, and checks whether any remains.
	 *
	 * @param root the slot's directory
	 * @return {@code true} if no recorded process remains alive
	 * @throws IOException if the slot's directories cannot be read
	 */
	private boolean stopRecordedProcesses(Path root) throws IOException
	{
		boolean ended = true;
		for (Path manifest : manifests(root))
		{
			JsonNode run;
			try
			{
				run = json.readTree(Files.readAllBytes(manifest));
			}
			catch (IOException | JacksonException _)
			{
				// A manifest that cannot be read records no process that CAT could identify
				continue;
			}
			if (run == null || !run.isObject())
				continue;
			ended &= stop(run.get("harness_pid"), run.get("harness_started_at"));
			ended &= stop(run.get("owner_pid"), run.get("owner_started_at"));
		}
		return ended;
	}

	/**
	 * Returns the manifests of the runs below a slot, which are at {@code <harness>/<run>/run.json}.
	 *
	 * @param root the slot's directory
	 * @return the manifests
	 * @throws IOException if the slot's directories cannot be read
	 */
	private static List<Path> manifests(Path root) throws IOException
	{
		List<Path> manifests = new ArrayList<>();
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
			return manifests;
		try (DirectoryStream<Path> harnesses = Files.newDirectoryStream(root))
		{
			for (Path harness : harnesses)
			{
				if (!Files.isDirectory(harness, LinkOption.NOFOLLOW_LINKS))
					continue;
				try (DirectoryStream<Path> runs = Files.newDirectoryStream(harness))
				{
					for (Path run : runs)
					{
						Path manifest = run.resolve("run.json");
						if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
							manifests.add(manifest);
					}
				}
			}
		}
		return manifests;
	}

	/**
	 * Asks one recorded process to end if it is alive. The current process is never asked, because a run that the
	 * current process runs in itself records it, and it cannot wait for its own end.
	 *
	 * @param pid   the recorded process identifier, which may be missing
	 * @param start the recorded start of the process, which may be missing
	 * @return {@code true} if the process is not alive
	 */
	private boolean stop(JsonNode pid, JsonNode start)
	{
		if (pid == null || start == null || !start.isString() ||
			!start.asString().matches(ProcessDescription.START_PATTERN))
		{
			return true;
		}
		long value;
		if (pid.isIntegralNumber() && pid.canConvertToLong())
			value = pid.asLong();
		else if (pid.isString() && pid.asString().matches("[1-9][0-9]{0,18}"))
			value = Long.parseLong(pid.asString());
		else
			return true;
		if (value <= 0 || value == ProcessHandle.current().pid())
			return true;
		if (!liveness.isAlive(value, start.asString()))
			return true;
		liveness.terminate(value, start.asString());
		return !liveness.isAlive(value, start.asString());
	}
}
