package com.holdmyspot.cat.common.test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Models the identity, liveness and descendants described by the JDK's {@link ProcessHandle} contract.
 * Both JDK termination operations record the same request; no operating-system process is created or stopped.
 */
public final class FakeProcessHandle implements ProcessHandle, ProcessHandle.Info
{
	private final long pid;
	private final Instant startedAt;
	private boolean running;
	private final List<FakeProcessHandle> children;

	/**
	 * Indicates whether the terminator asked this process to stop, without distinguishing the stopping mechanism.
	 */
	private boolean stopRequested;

	/**
	 * Creates a process with controlled identity and lifecycle inputs.
	 *
	 * @param pid       the process identifier
	 * @param startedAt the reported start time, or null when the operating system does not report it
	 * @param running   indicates whether the process is still running
	 * @param children  the processes it started
	 */
	public FakeProcessHandle(long pid, Instant startedAt, boolean running, FakeProcessHandle... children)
	{
		this.pid = pid;
		this.startedAt = startedAt;
		this.running = running;
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
		return Optional.ofNullable(startedAt);
	}

	@Override
	public boolean isAlive()
	{
		return running;
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
		running = false;
		return true;
	}

	@Override
	public boolean destroyForcibly()
	{
		stopRequested = true;
		running = false;
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
