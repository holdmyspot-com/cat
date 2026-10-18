package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Instant;
import java.util.Optional;

/**
 * The host session and process of a driver that holds a campaign's lock.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param session the identifier of the host session whose command runs the driver
 * @param pid the identifier of the process that runs the driver
 * @param startedAt when that process started, or {@code null} if the operating system does not report it
 */
public record LockHolder(String session, long pid, Instant startedAt)
{
	/**
	 * Creates a holder.
	 *
	 * @param session the identifier of the host session whose command runs the driver
	 * @param pid the identifier of the process that runs the driver
	 * @param startedAt when that process started, or {@code null} if the operating system does not report it
	 * @throws NullPointerException if {@code session} is null
	 * @throws IllegalArgumentException if {@code session} is blank or {@code pid} is not positive
	 */
	public LockHolder
	{
		requireThat(session, "session").isNotBlank();
		requireThat(pid, "pid").isPositive();
	}

	/**
	 * Returns the holder that the current process is for a host session.
	 *
	 * @param session the identifier of the host session whose command runs the current process
	 * @return the holder
	 * @throws NullPointerException if {@code session} is null
	 * @throws IllegalArgumentException if {@code session} is blank
	 */
	public static LockHolder current(String session)
	{
		ProcessHandle process = ProcessHandle.current();
		return new LockHolder(session, process.pid(), process.info().startInstant().orElse(null));
	}

	/**
	 * Indicates whether this holder is the current process or one of the processes that started it, directly or
	 * through other processes.
	 * <p>
	 * A process matches when its identifier equals {@link #pid()} and, if both start times are known, its start time
	 * equals {@link #startedAt()}, so a process that reused the identifier of a holder that ended does not match.
	 *
	 * @return {@code true} if the holder is the current process or one of its ancestors
	 */
	public boolean isCurrentProcessOrAncestor()
	{
		Optional<ProcessHandle> process = Optional.of(ProcessHandle.current());
		while (process.isPresent())
		{
			ProcessHandle handle = process.get();
			if (handle.pid() == pid && hasStartTime(handle))
				return true;
			process = handle.parent();
		}
		return false;
	}

	/**
	 * Indicates whether a process started when this holder did.
	 *
	 * @param process a process whose identifier equals {@link #pid()}
	 * @return {@code true} if the process started at {@link #startedAt()}, or either start time is unknown
	 */
	private boolean hasStartTime(ProcessHandle process)
	{
		if (startedAt == null)
			return true;
		Optional<Instant> processStart = process.info().startInstant();
		return processStart.isEmpty() || processStart.get().equals(startedAt);
	}
}
