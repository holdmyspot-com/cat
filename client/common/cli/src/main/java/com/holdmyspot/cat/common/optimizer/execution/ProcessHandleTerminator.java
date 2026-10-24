package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.secrets.ProcessHandleTerminatorSecrets;

import java.time.Instant;
import java.util.Optional;

/**
 * Terminates recorded processes through {@link ProcessHandle}.
 * <p>
 * A process identifier can be reused after its process exits, so a process is terminated only if the operating
 * system reports the recorded start time for it. Where the operating system reports no start time, nothing is
 * terminated. The current process is never terminated.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class ProcessHandleTerminator implements ProcessTerminator
{
	static
	{
		ProcessHandleTerminatorSecrets.setProcessHandleTerminatorAccess(ProcessHandleTerminator::new);
	}

	private final ProcessLookup processes;

	/**
	 * Creates a terminator.
	 */
	public ProcessHandleTerminator()
	{
		this(new ProcessLookup()
		{
			@Override
			public Optional<ProcessHandle> find(long pid)
			{
				return ProcessHandle.of(pid);
			}

			@Override
			public ProcessHandle current()
			{
				return ProcessHandle.current();
			}
		});
	}

	/**
	 * Creates a terminator using the supplied process lookup.
	 *
	 * @param processes finds recorded processes and identifies the driver
	 * @throws NullPointerException if {@code processes} is null
	 */
	private ProcessHandleTerminator(ProcessLookup processes)
	{
		requireThat(processes, "processes").isNotNull();
		this.processes = processes;
	}

	@Override
	public boolean terminate(long pid, Instant startedAt)
	{
		requireThat(pid, "pid").isPositive();
		requireThat(startedAt, "startedAt").isNotNull();
		Optional<ProcessHandle> match = processes.find(pid).
			filter(handle -> handle.info().startInstant().filter(startedAt::equals).isPresent());
		if (match.isEmpty() || !match.get().isAlive() || match.get().equals(processes.current()))
			return false;
		ProcessHandle process = match.get();
		// A runner's child processes can outlive a killed driver, so request termination of descendants too
		process.descendants().forEach(ProcessHandle::destroy);
		process.destroy();
		return true;
	}

	/**
	 * Finds processes by identifier and identifies the driver process.
	 * <p>
	 * Implementations used by a shared terminator must be thread-safe.
	 */
	public interface ProcessLookup
	{
		/**
		 * Finds the process with the supplied identifier.
		 *
		 * @param pid the process identifier
		 * @return the process, or an empty value if it no longer exists
		 */
		Optional<ProcessHandle> find(long pid);

		/**
		 * Returns the driver process.
		 *
		 * @return the driver process
		 */
		ProcessHandle current();
	}
}
