package com.holdmyspot.cat.common.runner.launcher.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.ProcessTable;
import java.util.HashMap;
import java.util.Map;

/**
 * An in-memory process table that the test edits: it starts processes and ends them. Each start has the form of a
 * Linux process's: the boot's identifier, the letter {@code t}, and the clock ticks since the boot.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeProcessTable implements ProcessTable
{
	private static final String BOOT = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
	private final Map<Long, ProcessIdentity> processes = new HashMap<>();
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
	 * @param pid the process identifier, which no live process has
	 * @return its identity
	 */
	public ProcessIdentity start(long pid)
	{
		synchronized (this)
		{
			requireThat(processes.containsKey(pid), "processes.containsKey(pid)").isFalse();
			generation += 1;
			ProcessIdentity identity = new ProcessIdentity(pid, BOOT + "t" + (pid * 1000 + generation));
			processes.put(pid, identity);
			return identity;
		}
	}

	/**
	 * Ends a process.
	 *
	 * @param process the process
	 */
	public void end(ProcessIdentity process)
	{
		synchronized (this)
		{
			processes.remove(process.pid(), process);
		}
	}

	@Override
	public boolean isAlive(ProcessIdentity process)
	{
		synchronized (this)
		{
			return process.equals(processes.get(process.pid()));
		}
	}
}
