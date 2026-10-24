package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator;
import com.holdmyspot.cat.common.optimizer.execution.ProcessHandleTerminator.ProcessLookup;
import com.holdmyspot.cat.common.secrets.ProcessHandleTerminatorSecrets;
import org.testng.annotations.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Covers requests to stop recorded processes using in-memory processes that spawn nothing.
 */
public final class ProcessHandleTerminatorTest
{
	/**
	 * Creates the test.
	 */
	public ProcessHandleTerminatorTest()
	{
	}

	/**
	 * Verifies that a reused identifier or an unknown start time leaves the process and its children alone.
	 */
	@Test
	public void unverifiedProcessIsLeftAlone()
	{
		// Requirement: R-E11
		FakeProcess driver = new FakeProcess(1, Instant.EPOCH, true);
		FakeProcess child = new FakeProcess(3, Instant.EPOCH, true);
		for (Instant actualStart : new Instant[]{Instant.EPOCH.plusSeconds(1), null})
		{
			FakeProcess process = new FakeProcess(2, actualStart, true, child);
			ProcessHandleTerminator terminator = terminator(driver, process);

			boolean terminated = terminator.terminate(process.pid(), Instant.EPOCH);

			requireThat(terminated, "terminated").isFalse();
			requireThat(process.stopRequested, "process.stopRequested").isFalse();
			requireThat(child.stopRequested, "child.stopRequested").isFalse();
			requireThat(driver.stopRequested, "driver.stopRequested").isFalse();
		}
	}

	/**
	 * Verifies that the recorded leftover process and the processes it started are asked to stop, leaving others alone.
	 */
	@Test
	public void matchingProcessTreeIsAskedToStop()
	{
		// Requirement: R-E11
		FakeProcess driver = new FakeProcess(1, Instant.EPOCH, true);
		FakeProcess grandchild = new FakeProcess(4, Instant.EPOCH, true);
		FakeProcess child = new FakeProcess(3, Instant.EPOCH, true, grandchild);
		FakeProcess process = new FakeProcess(2, Instant.EPOCH, true, child);
		FakeProcess unrelated = new FakeProcess(5, Instant.EPOCH, true);
		ProcessHandleTerminator terminator = terminator(driver, process, child, grandchild, unrelated);

		boolean terminated = terminator.terminate(process.pid(), Instant.EPOCH);

		requireThat(terminated, "terminated").isTrue();
		requireThat(process.stopRequested, "process.stopRequested").isTrue();
		requireThat(child.stopRequested, "child.stopRequested").isTrue();
		requireThat(grandchild.stopRequested, "grandchild.stopRequested").isTrue();
		requireThat(unrelated.stopRequested, "unrelated.stopRequested").isFalse();
		requireThat(driver.stopRequested, "driver.stopRequested").isFalse();
	}

	/**
	 * Verifies that the driver's own process and its children are left alone even when the recorded identity matches.
	 */
	@Test
	public void driverProcessIsLeftAlone()
	{
		// Requirement: R-E11
		FakeProcess child = new FakeProcess(2, Instant.EPOCH, true);
		FakeProcess driver = new FakeProcess(1, Instant.EPOCH, true, child);
		ProcessHandleTerminator terminator = terminator(driver, driver, child);

		boolean terminated = terminator.terminate(driver.pid(), Instant.EPOCH);

		requireThat(terminated, "terminated").isFalse();
		requireThat(driver.stopRequested, "driver.stopRequested").isFalse();
		requireThat(child.stopRequested, "child.stopRequested").isFalse();
	}

	/**
	 * Verifies that an exited or missing recorded process reports no termination and leaves other processes alone.
	 */
	@Test
	public void exitedOrMissingProcessIsLeftAlone()
	{
		// Requirement: R-E11
		FakeProcess driver = new FakeProcess(1, Instant.EPOCH, true);
		FakeProcess child = new FakeProcess(3, Instant.EPOCH, true);
		FakeProcess exited = new FakeProcess(2, Instant.EPOCH, false, child);
		ProcessHandleTerminator terminator = terminator(driver, exited, child);

		boolean terminatedExited = terminator.terminate(exited.pid(), Instant.EPOCH);
		boolean terminatedMissing = terminator.terminate(4, Instant.EPOCH);

		requireThat(terminatedExited, "terminatedExited").isFalse();
		requireThat(terminatedMissing, "terminatedMissing").isFalse();
		requireThat(exited.stopRequested, "exited.stopRequested").isFalse();
		requireThat(child.stopRequested, "child.stopRequested").isFalse();
		requireThat(driver.stopRequested, "driver.stopRequested").isFalse();
	}

	/**
	 * Creates a terminator with an in-memory process table.
	 *
	 * @param driver    the driver's own process
	 * @param processes the processes available by identifier
	 * @return the terminator
	 */
	private static ProcessHandleTerminator terminator(FakeProcess driver, FakeProcess... processes)
	{
		return ProcessHandleTerminatorSecrets.getProcessHandleTerminatorAccess().create(new ProcessLookup()
		{
			@Override
			public Optional<ProcessHandle> find(long pid)
			{
				return Stream.of(processes).filter(process -> process.pid() == pid).
					map(ProcessHandle.class::cast).findFirst();
			}

			@Override
			public ProcessHandle current()
			{
				return driver;
			}
		});
	}

	/**
	 * Models the identity, liveness and descendants described by the JDK's {@link ProcessHandle} contract.
	 * Both JDK termination operations record the same request; no operating-system process is created or stopped.
	 */
	private static final class FakeProcess implements ProcessHandle, ProcessHandle.Info
	{
		private final long pid;
		private final Instant startedAt;
		private final boolean running;
		private final List<FakeProcess> children;

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
		private FakeProcess(long pid, Instant startedAt, boolean running, FakeProcess... children)
		{
			this.pid = pid;
			this.startedAt = startedAt;
			this.running = running;
			this.children = List.of(children);
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
			return true;
		}

		@Override
		public boolean destroyForcibly()
		{
			stopRequested = true;
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
}
