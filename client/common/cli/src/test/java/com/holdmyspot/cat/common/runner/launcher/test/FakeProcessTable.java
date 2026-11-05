package com.holdmyspot.cat.common.runner.launcher.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory process table that the test edits: it starts processes under a parent with a command line, ends them,
 * and lets a later process reuse an identifier with another start instant.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeProcessTable implements ProcessTable
{
	private static final Instant BOOT = Instant.parse("2026-11-05T12:00:00Z");
	private final Map<Long, Entry> processes = new HashMap<>();
	private int generation;

	/**
	 * Creates an empty table.
	 */
	public FakeProcessTable()
	{
	}

	/**
	 * Starts a process.
	 *
	 * @param pid       the process identifier, which no live process has
	 * @param parent    the parent, or empty for a process without one
	 * @param arguments the arguments of its command line
	 * @return its identity
	 */
	public ProcessIdentity start(long pid, Optional<ProcessIdentity> parent, List<String> arguments)
	{
		synchronized (this)
		{
			requireThat(processes.containsKey(pid), "processes.containsKey(pid)").isFalse();
			generation += 1;
			ProcessIdentity identity = new ProcessIdentity(pid, BOOT.plusSeconds(pid).plusMillis(generation));
			processes.put(pid, new Entry(identity, parent, List.copyOf(arguments)));
			return identity;
		}
	}

	/**
	 * Ends a process. Its children keep running, without a parent.
	 *
	 * @param process the process
	 */
	public void end(ProcessIdentity process)
	{
		synchronized (this)
		{
			Entry entry = processes.get(process.pid());
			if (entry != null && entry.identity().equals(process))
				processes.remove(process.pid());
		}
	}

	/**
	 * Ends a process and starts another one with the same identifier, parent and command line.
	 *
	 * @param process the process
	 * @return the new process's identity
	 */
	public ProcessIdentity reuse(ProcessIdentity process)
	{
		synchronized (this)
		{
			Entry entry = processes.get(process.pid());
			requireThat(entry, "entry").isNotNull();
			end(process);
			return start(process.pid(), entry.parent(), entry.arguments());
		}
	}

	@Override
	public List<ProcessIdentity> all()
	{
		synchronized (this)
		{
			List<ProcessIdentity> identities = new ArrayList<>();
			for (Entry entry : processes.values())
				identities.add(entry.identity());
			return identities;
		}
	}

	@Override
	public List<ProcessIdentity> descendants(ProcessIdentity process)
	{
		synchronized (this)
		{
			List<ProcessIdentity> descendants = new ArrayList<>();
			if (!isAlive(process))
				return descendants;
			for (Entry entry : processes.values())
			{
				Optional<ProcessIdentity> ancestor = parent(entry.identity());
				while (ancestor.isPresent() && !ancestor.get().equals(process))
					ancestor = parent(ancestor.get());
				if (ancestor.isPresent())
					descendants.add(entry.identity());
			}
			return descendants;
		}
	}

	@Override
	public Optional<ProcessIdentity> parent(ProcessIdentity process)
	{
		synchronized (this)
		{
			if (!isAlive(process))
				return Optional.empty();
			return processes.get(process.pid()).parent().filter(this::isAlive);
		}
	}

	@Override
	public Optional<List<String>> arguments(ProcessIdentity process)
	{
		synchronized (this)
		{
			if (!isAlive(process))
				return Optional.empty();
			return Optional.of(processes.get(process.pid()).arguments());
		}
	}

	@Override
	public boolean isAlive(ProcessIdentity process)
	{
		synchronized (this)
		{
			Entry entry = processes.get(process.pid());
			return entry != null && entry.identity().equals(process);
		}
	}

	/**
	 * One live process.
	 *
	 * @param identity  its identity
	 * @param parent    its parent, or empty for none
	 * @param arguments the arguments of its command line
	 */
	private record Entry(ProcessIdentity identity, Optional<ProcessIdentity> parent, List<String> arguments)
	{
	}
}
