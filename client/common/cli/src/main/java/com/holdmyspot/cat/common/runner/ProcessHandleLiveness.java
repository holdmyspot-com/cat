package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.secrets.ProcessHandleLivenessSecrets;

import java.time.Instant;
import java.util.Optional;
import java.util.function.LongFunction;

/**
 * Checks whether a Java {@link ProcessHandle} still identifies the process recorded for a harness execution.
 */
public final class ProcessHandleLiveness implements HarnessProcessLiveness
{
	static
	{
		ProcessHandleLivenessSecrets.setProcessHandleLivenessAccess(ProcessHandleLiveness::new);
	}

	/**
	 * Finds a process of the operating system's process table by its identifier.
	 */
	private final LongFunction<Optional<ProcessHandle>> processes;

	/**
	 * Creates a checker of the operating system's processes.
	 */
	public ProcessHandleLiveness()
	{
		this(ProcessHandle::of);
	}

	/**
	 * Creates a checker that finds processes through the supplied lookup.
	 *
	 * @param processes finds the process with an identifier, or nothing if no such process exists
	 * @throws NullPointerException if {@code processes} is null
	 */
	private ProcessHandleLiveness(LongFunction<Optional<ProcessHandle>> processes)
	{
		requireThat(processes, "processes").isNotNull();
		this.processes = processes;
	}

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
	 * @param pid       the positive process identifier
	 * @param startedAt the process start marker
	 * @return the matching process, if present
	 */
	private Optional<ProcessHandle> matchingProcess(long pid, Instant startedAt)
	{
		assert pid > 0;
		assert startedAt != null;
		return processes.apply(pid).filter(handle -> handle.info().startInstant().filter(startedAt::equals).
			isPresent());
	}
}
