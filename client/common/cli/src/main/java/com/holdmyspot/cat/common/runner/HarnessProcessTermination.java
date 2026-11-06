package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Terminates a harness process and every descendant that the process table shows.
 */
public final class HarnessProcessTermination
{
	private static final Duration GRACEFUL_TERMINATION_TIMEOUT = Duration.ofSeconds(5);

	/**
	 * Stops the process tree before its runner publishes a terminal result.
	 *
	 * @param process     the live harness process
	 * @param harnessName the harness name for diagnostics
	 * @throws IOException if interruption prevents termination from being confirmed
	 */
	public static void terminate(Process process, String harnessName) throws IOException
	{
		List<ProcessHandle> descendants = descendants(process);
		descendants.forEach(ProcessHandle::destroy);
		if (process.isAlive())
			process.destroy();
		try
		{
			if (!process.waitFor(GRACEFUL_TERMINATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
			{
				descendants(process).forEach(ProcessHandle::destroyForcibly);
				process.destroyForcibly();
				process.waitFor();
			}
			descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
			descendants(process).stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for " + harnessName + " and its descendants to exit", e);
		}
	}

	/**
	 * Immediately stops a process tree during JVM shutdown without waiting for each process to report its exit.
	 *
	 * @param process the process whose inherited children must not outlive the owning JVM
	 */
	public static void terminateImmediately(Process process)
	{
		descendants(process).forEach(ProcessHandle::destroyForcibly);
		if (process.isAlive())
			process.destroyForcibly();
	}

	/**
	 * Returns the processes that stopping a child's process tree stops.
	 *
	 * @param child   the child's process identifier
	 * @param parents a snapshot of the process table: the parent's identifier of each process that has a parent
	 * @return the child, then each process that descends from it in the snapshot, each after its parent
	 * @throws NullPointerException if {@code parents} is null
	 */
	public static List<Long> tree(long child, Map<Long, Long> parents)
	{
		requireThat(parents, "parents").isNotNull();
		Map<Long, List<Long>> children = new HashMap<>();
		for (Map.Entry<Long, Long> entry : parents.entrySet())
			children.computeIfAbsent(entry.getValue(), _ -> new ArrayList<>()).add(entry.getKey());
		Set<Long> tree = new LinkedHashSet<>();
		Deque<Long> pending = new ArrayDeque<>(List.of(child));
		while (!pending.isEmpty())
		{
			long next = pending.removeFirst();
			// A snapshot whose identifiers were reused can link a process back to an ancestor
			if (tree.add(next))
				pending.addAll(children.getOrDefault(next, List.of()));
		}
		return List.copyOf(tree);
	}

	/**
	 * Returns the live processes that descend from a process, as the process table shows them now.
	 *
	 * @param process a process
	 * @return its descendants, each after its parent; none if the process has no operating-system identifier
	 */
	private static List<ProcessHandle> descendants(Process process)
	{
		long child;
		try
		{
			child = process.pid();
		}
		catch (UnsupportedOperationException _)
		{
			// A Process implementation without an operating-system identifier has no descendants in the process table
			return List.of();
		}
		Map<Long, Long> parents = new HashMap<>();
		ProcessHandle.allProcesses().forEach(handle -> handle.parent().ifPresent(parent ->
			parents.put(handle.pid(), parent.pid())));
		List<ProcessHandle> descendants = new ArrayList<>();
		List<Long> tree = tree(child, parents);
		for (long pid : tree.subList(1, tree.size()))
			ProcessHandle.of(pid).ifPresent(descendants::add);
		return descendants;
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private HarnessProcessTermination()
	{
	}
}
