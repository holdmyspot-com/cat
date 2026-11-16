package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import com.holdmyspot.cat.common.secrets.ProcessHandleLivenessSecrets;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests that a live process without an observed start cannot be treated as an ended or safe-to-stop process.
 */
public final class ProcessHandleLivenessTest
{
	/**
	 * The start recorded for the process whose current start is unavailable.
	 */
	private static final String RECORDED_START = "0f1e2d3c4b5a69788796a5b4c3d2e1f0t4000";

	/**
	 * Creates the test.
	 */
	public ProcessHandleLivenessTest()
	{
	}

	/**
	 * Verifies that a live process with no observed start produces an operational error when its liveness is checked.
	 *
	 * @throws IOException if the test process metadata cannot be read
	 */
	@Test
	public void missingObservedStartFailsIsAlive() throws IOException
	{
		ProbeProcessHandle process = new ProbeProcessHandle(ProcessHandle.current().pid() + 100_000,
			new AtomicInteger());
		AtomicInteger handleLookups = new AtomicInteger();
		HarnessProcessLiveness liveness = liveness(process, handleLookups);
		IOException failure = requireIOException(() -> liveness.isAlive(process.pid(), RECORDED_START));

		requireThat(failure.getMessage(), "failureMessage").contains(Long.toString(process.pid()));
		requireThat(failure.getMessage(), "failureMessage").contains(RECORDED_START);
		requireThat(handleLookups.get(), "handleLookups").isEqualTo(0);
		requireThat(process.terminationRequests().get(), "terminationRequests").isEqualTo(0);
	}

	/**
	 * Verifies that a live process with no observed start is not signalled by a termination request.
	 *
	 * @throws IOException if the test process metadata cannot be read
	 */
	@Test
	public void missingObservedStartFailsTerminate() throws IOException
	{
		ProbeProcessHandle process = new ProbeProcessHandle(ProcessHandle.current().pid() + 100_000,
			new AtomicInteger());
		AtomicInteger handleLookups = new AtomicInteger();
		HarnessProcessLiveness liveness = liveness(process, handleLookups);
		IOException failure = requireIOException(() -> liveness.terminate(process.pid(), RECORDED_START));

		requireThat(failure.getMessage(), "failureMessage").contains(Long.toString(process.pid()));
		requireThat(failure.getMessage(), "failureMessage").contains(RECORDED_START);
		requireThat(handleLookups.get(), "handleLookups").isEqualTo(0);
		requireThat(process.terminationRequests().get(), "terminationRequests").isEqualTo(0);
	}

	/**
	 * Creates a liveness checker whose process table reports a live process without a start.
	 *
	 * @param process the live process
	 * @param handleLookups counts lookups of its process handle
	 * @return the checker
	 */
	private static HarnessProcessLiveness liveness(ProbeProcessHandle process, AtomicInteger handleLookups)
	{
		HarnessProcesses processes = pid -> Optional.of(new ProcessDescription(pid, Optional.empty(), Optional.empty(),
			Optional.empty(), Optional.empty()));
		return ProcessHandleLivenessSecrets.getProcessHandleLivenessAccess().create(processes, pid ->
		{
			handleLookups.addAndGet(1);
			return Optional.of(process);
		});
	}

	/**
	 * Returns the operational error expected from an identity check that cannot observe a start.
	 *
	 * @param operation the identity check
	 * @return the error
	 * @throws IOException if no error was thrown
	 */
	private static IOException requireIOException(LivenessOperation operation) throws IOException
	{
		try
		{
			operation.check();
		}
		catch (IOException e)
		{
			return e;
		}
		throw new AssertionError("A live process without an observed start was accepted");
	}

	/**
	 * An identity check that may fail to observe a process start.
	 */
	@FunctionalInterface
	private interface LivenessOperation
	{
		/**
		 * Checks one process identity.
		 *
		 * @return whether the recorded process is alive
		 * @throws IOException if the process start cannot be observed
		 */
		boolean check() throws IOException;
	}

	/**
	 * A live process handle that counts requests to signal it.
	 *
	 * @param pid the process identifier
	 * @param terminationRequests counts requests to signal the process
	 */
	private record ProbeProcessHandle(long pid, AtomicInteger terminationRequests) implements ProcessHandle
	{
		@Override
		public long pid()
		{
			return pid;
		}

		@Override
		public Optional<ProcessHandle> parent()
		{
			return Optional.empty();
		}

		@Override
		public Stream<ProcessHandle> children()
		{
			return Stream.empty();
		}

		@Override
		public Stream<ProcessHandle> descendants()
		{
			return Stream.empty();
		}

		@Override
		public Info info()
		{
			return ProcessHandle.current().info();
		}

		@Override
		public CompletableFuture<ProcessHandle> onExit()
		{
			return CompletableFuture.completedFuture(this);
		}

		@Override
		public boolean supportsNormalTermination()
		{
			return true;
		}

		@Override
		public boolean destroy()
		{
			terminationRequests.addAndGet(1);
			return true;
		}

		@Override
		public boolean destroyForcibly()
		{
			terminationRequests.addAndGet(1);
			return true;
		}

		@Override
		public boolean isAlive()
		{
			return true;
		}

		@Override
		public int compareTo(ProcessHandle other)
		{
			return Long.compare(pid, other.pid());
		}
	}
}
