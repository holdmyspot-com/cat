package com.holdmyspot.cat.common.runner.policy.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.policy.HarnessProcesses;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.IOException;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * The behaviour of {@link HarnessProcesses#find(long)} on which the checks of a recorded process rest: a running
 * process is found with the same {@link ProcessDescription#start() start} on every lookup, a running process whose
 * start the system does not report is found without one, and a process that is not running is not found. Each
 * implementation's test extends this class.
 */
public abstract class HarnessProcessesContract
{
	/**
	 * Creates the contract tests.
	 */
	protected HarnessProcessesContract()
	{
	}

	/**
	 * Creates the processes that the tests look up.
	 *
	 * @return the processes
	 * @throws IOException if they cannot be created
	 */
	protected abstract Fixture create() throws IOException;

	/**
	 * The processes that the tests look up, and the lookup that reports them.
	 */
	protected interface Fixture extends AutoCloseable
	{
		/**
		 * Returns the lookup.
		 *
		 * @return the lookup
		 */
		HarnessProcesses processes();

		/**
		 * Returns a running process whose start the system reports.
		 *
		 * @return the process's identifier
		 */
		long running();

		/**
		 * Returns a running process whose start the system does not report.
		 *
		 * @return the process's identifier
		 */
		long runningWithoutStart();

		/**
		 * Returns an identifier that no running process has.
		 *
		 * @return the identifier
		 */
		long notRunning();

		/**
		 * Releases the processes.
		 *
		 * @throws IOException if they cannot be released
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * Verifies that a running process is found, with the same start on every lookup.
	 *
	 * @throws IOException if the processes cannot be created
	 */
	@Test
	public void findsRunningProcessWithItsStart() throws IOException
	{
		try (Fixture fixture = create())
		{
			Optional<ProcessDescription> first = fixture.processes().find(fixture.running());
			Optional<ProcessDescription> second = fixture.processes().find(fixture.running());

			requireThat(first.map(ProcessDescription::processId), "first.processId").
				isEqualTo(Optional.of(fixture.running()));
			requireThat(first.flatMap(ProcessDescription::start), "first.start").isPresent();
			requireThat(second.flatMap(ProcessDescription::start), "second.start").
				isEqualTo(first.flatMap(ProcessDescription::start));
		}
	}

	/**
	 * Verifies that a running process whose start the system does not report is found without a start.
	 *
	 * @throws IOException if the processes cannot be created
	 */
	@Test
	public void findsRunningProcessWithoutStart() throws IOException
	{
		try (Fixture fixture = create())
		{
			Optional<ProcessDescription> found = fixture.processes().find(fixture.runningWithoutStart());

			requireThat(found, "found").isPresent();
			requireThat(found.flatMap(ProcessDescription::start), "start").isEmpty();
		}
	}

	/**
	 * Verifies that a process that is not running is not found.
	 *
	 * @throws IOException if the processes cannot be created
	 */
	@Test
	public void findsNoProcessThatIsNotRunning() throws IOException
	{
		try (Fixture fixture = create())
		{
			requireThat(fixture.processes().find(fixture.notRunning()), "found").isEmpty();
		}
	}
}
