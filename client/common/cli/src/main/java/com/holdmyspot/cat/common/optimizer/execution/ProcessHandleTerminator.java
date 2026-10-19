package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
	/**
	 * Creates a terminator.
	 */
	public ProcessHandleTerminator()
	{
	}

	@Override
	public boolean terminate(long pid, Instant startedAt)
	{
		requireThat(pid, "pid").isPositive();
		requireThat(startedAt, "startedAt").isNotNull();
		Optional<ProcessHandle> match = ProcessHandle.of(pid).
			filter(handle -> handle.info().startInstant().filter(startedAt::equals).isPresent());
		if (match.isEmpty() || !match.get().isAlive() || match.get().equals(ProcessHandle.current()))
			return false;
		ProcessHandle process = match.get();
		// A runner's child processes can outlive a killed driver, so request termination of descendants too
		process.descendants().forEach(ProcessHandle::destroy);
		process.destroy();
		return true;
	}
}
