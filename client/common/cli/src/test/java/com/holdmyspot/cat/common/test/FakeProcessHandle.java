package com.holdmyspot.cat.common.test;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Models the identity, liveness and descendants described by the JDK's {@link ProcessHandle} contract, and the
 * {@link ProcessDescription#start() start} that {@link HarnessProcesses#system()} reports for a process.
 * Both JDK termination operations record the same request; no operating-system process is created or stopped.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe, as the JDK's process handles are.
 */
public final class FakeProcessHandle implements ProcessHandle, ProcessHandle.Info
{
	/**
	 * Returns a process table that holds exactly the given processes: it finds each of them by its identifier, as
	 * {@link ProcessHandle#of(long)} finds a process of the operating system, and finds no process for any other
	 * identifier.
	 *
	 * @param processes the processes of the table, each with its own identifier
	 * @return the lookup of a process by its identifier
	 */
	public static LongFunction<Optional<ProcessHandle>> table(FakeProcessHandle... processes)
	{
		List<FakeProcessHandle> table = List.of(processes);
		return pid -> table.stream().filter(process -> process.pid == pid).map(ProcessHandle.class::cast).findFirst();
	}

	/**
	 * Returns the starts of exactly the given processes, as {@link HarnessProcesses#system()} reports the starts of
	 * the operating system's processes: it finds each of them that is running, with the start it was created with,
	 * and finds no process for any other identifier.
	 *
	 * @param processes the processes, each with its own identifier
	 * @return the processes' starts
	 */
	public static HarnessProcesses processes(FakeProcessHandle... processes)
	{
		List<FakeProcessHandle> table = List.of(processes);
		return pid -> table.stream().filter(process -> process.pid == pid && process.isAlive()).findFirst().
			map(process -> new ProcessDescription(pid, Optional.ofNullable(process.start), Optional.empty(),
				Optional.empty(), Optional.empty()));
	}

	private final long pid;
	private final String start;
	/**
	 * A count of one represents a running process; zero represents its completion. The launcher can share this
	 * completion with its Process view so either view's termination releases the same waiters.
	 */
	private final CountDownLatch ended;
	private final List<FakeProcessHandle> children;

	/**
	 * Indicates whether the terminator asked this process to stop, without distinguishing the stopping mechanism.
	 */
	private volatile boolean stopRequested;

	/**
	 * Creates a process with controlled identity and lifecycle inputs.
	 *
	 * @param pid      the process identifier
	 * @param start    the reported {@link ProcessDescription#start() start}, or null when the operating system does
	 *                 not report it
	 * @param running  indicates whether the process is still running
	 * @param children the processes it started
	 */
	public FakeProcessHandle(long pid, String start, boolean running, FakeProcessHandle... children)
	{
		this(pid, start, new CountDownLatch(1), children);
		if (!running)
			ended.countDown();
	}

	/**
	 * Creates a handle sharing a process's completion. This handle retains {@code ended}; either its owner or this
	 * handle's termination methods may count it down to complete the process.
	 *
	 * @param pid      the process identifier
	 * @param start    the reported start, or null when it is unavailable
	 * @param ended    the shared completion, with count one while running or zero after exit
	 * @param children the processes it started
	 * @throws NullPointerException if {@code ended} is null
	 * @throws IllegalArgumentException if {@code ended} has a count greater than one
	 */
	public FakeProcessHandle(long pid, String start, CountDownLatch ended, FakeProcessHandle... children)
	{
		requireThat(ended.getCount(), "ended.getCount()").isLessThanOrEqualTo(1L);
		this.pid = pid;
		this.start = start;
		this.ended = ended;
		this.children = List.of(children);
	}

	/**
	 * Indicates whether termination of this process was requested.
	 *
	 * @return true if either termination operation was invoked
	 */
	public boolean stopRequested()
	{
		return stopRequested;
	}

	@Override
	public long pid()
	{
		return pid;
	}

	@Override
	public Info info()
	{
		return this;
	}

	@Override
	public Optional<Instant> startInstant()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isAlive()
	{
		return ended.getCount() != 0;
	}

	@Override
	public Stream<ProcessHandle> children()
	{
		return children.stream().map(ProcessHandle.class::cast);
	}

	@Override
	public Stream<ProcessHandle> descendants()
	{
		return children().flatMap(child -> Stream.concat(Stream.of(child), child.descendants()));
	}

	@Override
	public boolean destroy()
	{
		stopRequested = true;
		ended.countDown();
		return true;
	}

	@Override
	public boolean destroyForcibly()
	{
		stopRequested = true;
		ended.countDown();
		return true;
	}

	@Override
	public boolean supportsNormalTermination()
	{
		return true;
	}

	@Override
	public int compareTo(ProcessHandle other)
	{
		return Long.compare(pid, other.pid());
	}

	@Override
	public boolean equals(Object other)
	{
		return other instanceof ProcessHandle process && pid == process.pid();
	}

	@Override
	public int hashCode()
	{
		return Long.hashCode(pid);
	}

	@Override
	public Optional<ProcessHandle> parent()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public CompletableFuture<ProcessHandle> onExit()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Optional<String> command()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Optional<String> commandLine()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Optional<String[]> arguments()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Optional<Duration> totalCpuDuration()
	{
		throw new UnsupportedOperationException();
	}

	@Override
	public Optional<String> user()
	{
		throw new UnsupportedOperationException();
	}
}
