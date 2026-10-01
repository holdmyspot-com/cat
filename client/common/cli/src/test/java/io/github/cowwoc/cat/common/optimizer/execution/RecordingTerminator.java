package io.github.cowwoc.cat.common.optimizer.execution;

import io.github.cowwoc.cat.common.optimizer.execution.LeftoverRuns.RecordedProcess;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ProcessTerminator} that records the processes it is asked to terminate and terminates none.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RecordingTerminator implements ProcessTerminator
{
	private final Object lock = new Object();
	private final List<RecordedProcess> terminated = new ArrayList<>();

	/**
	 * Creates a terminator that has terminated nothing.
	 */
	public RecordingTerminator()
	{
	}

	@Override
	public boolean terminate(long pid, Instant startedAt)
	{
		synchronized (lock)
		{
			terminated.add(new RecordedProcess(pid, startedAt));
			return true;
		}
	}

	/**
	 * Returns the processes that the driver asked to terminate.
	 *
	 * @return the processes, in request order
	 */
	public List<RecordedProcess> terminated()
	{
		synchronized (lock)
		{
			return List.copyOf(terminated);
		}
	}
}
