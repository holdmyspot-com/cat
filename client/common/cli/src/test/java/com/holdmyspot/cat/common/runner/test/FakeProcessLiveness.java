package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * A {@link HarnessProcessLiveness} that stands in for the operating system's process table without starting or
 * stopping any real process.
 * <p>
 * It holds a set of live processes, each identified by its identifier and start time. As
 * {@code ProcessHandleLiveness} does for the real table, it reports a process alive only when both match a live
 * process, so a live process that reuses a recorded identifier with another start time is not the recorded process.
 * A termination request for a live process removes it from the table and is remembered.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeProcessLiveness implements HarnessProcessLiveness
{
	private final Object lock = new Object();
	private final Set<Process> live = new HashSet<>();
	private final Set<Process> terminated = new HashSet<>();

	/**
	 * Creates a process table in which no process is alive.
	 */
	public FakeProcessLiveness()
	{
	}

	/**
	 * Adds a live process to the table.
	 *
	 * @param pid       the process identifier
	 * @param startedAt the process's start time
	 * @return this table
	 * @throws NullPointerException     if {@code startedAt} is null
	 * @throws IllegalArgumentException if {@code pid} is not positive
	 */
	public FakeProcessLiveness alive(long pid, Instant startedAt)
	{
		Process process = new Process(pid, startedAt);
		synchronized (lock)
		{
			live.add(process);
		}
		return this;
	}

	@Override
	public boolean isAlive(long pid, Instant startedAt)
	{
		Process process = new Process(pid, startedAt);
		synchronized (lock)
		{
			return live.contains(process);
		}
	}

	@Override
	public boolean terminate(long pid, Instant startedAt)
	{
		Process process = new Process(pid, startedAt);
		synchronized (lock)
		{
			if (!live.remove(process))
				return false;
			terminated.add(process);
			return true;
		}
	}

	/**
	 * Indicates whether termination of a live process was requested.
	 *
	 * @param pid       the process identifier
	 * @param startedAt the process's start time
	 * @return {@code true} if the process was alive when its termination was requested
	 * @throws NullPointerException     if {@code startedAt} is null
	 * @throws IllegalArgumentException if {@code pid} is not positive
	 */
	public boolean wasTerminated(long pid, Instant startedAt)
	{
		Process process = new Process(pid, startedAt);
		synchronized (lock)
		{
			return terminated.contains(process);
		}
	}

	/**
	 * A process in the table.
	 *
	 * @param pid       the process identifier
	 * @param startedAt the process's start time
	 */
	private record Process(long pid, Instant startedAt)
	{
		/**
		 * Creates a process.
		 *
		 * @param pid       the process identifier
		 * @param startedAt the process's start time
		 * @throws NullPointerException     if {@code startedAt} is null
		 * @throws IllegalArgumentException if {@code pid} is not positive
		 */
		private Process
		{
			requireThat(pid, "pid").isPositive();
			requireThat(startedAt, "startedAt").isNotNull();
		}
	}
}
