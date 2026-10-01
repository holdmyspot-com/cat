package io.github.cowwoc.cat.common.runner;

import java.time.Instant;
import java.util.Optional;


/**
 * Checks whether a Java {@link ProcessHandle} still identifies the process recorded for a harness execution.
 */
final class ProcessHandleLiveness implements HarnessProcessLiveness
{
	@Override
	public boolean isAlive(long pid, Instant startedAt)
	{
		assert pid > 0;
		assert startedAt != null;
		return matchingProcess(pid, startedAt).filter(ProcessHandle::isAlive).isPresent();
	}

	@Override
	public boolean terminate(long pid, Instant startedAt)
	{
		assert pid > 0;
		assert startedAt != null;
		return matchingProcess(pid, startedAt).filter(ProcessHandle::isAlive).map(ProcessHandle::destroy).orElse(false);
	}

	/**
	 * Finds a process only if both its identifier and start marker match.
	 *
	 * @param pid the positive process identifier
	 *
	 * @param startedAt the process start marker
	 *
	 * @return the matching process, if present
	 */
	private static Optional<ProcessHandle> matchingProcess(long pid, Instant startedAt)
	{
		assert pid > 0;
		assert startedAt != null;
		return ProcessHandle.of(pid).filter(handle -> handle.info().startInstant().filter(startedAt::equals).
				isPresent());
	}
}
