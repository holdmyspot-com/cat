package com.holdmyspot.cat.common.runner.policy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * What the operating system reports about a running harness process.
 *
 * @param processId the process ID
 * @param start     the instant that the process started, or empty if the system does not report it
 * @param command   the program that the process runs, or empty if the system does not report it
 * @param arguments the arguments after the program, or empty if the system does not report them
 */
public record HarnessProcess(long processId, Optional<Instant> start, Optional<String> command,
	Optional<List<String>> arguments)
{
	/**
	 * Creates a process.
	 *
	 * @param processId the process ID
	 * @param start     the instant that the process started, or empty if the system does not report it
	 * @param command   the program that the process runs, or empty if the system does not report it
	 * @param arguments the arguments after the program, or empty if the system does not report them
	 * @throws NullPointerException if any of the arguments are null, or {@code arguments} contains null
	 */
	public HarnessProcess
	{
		requireThat(start, "start").isNotNull();
		requireThat(command, "command").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		arguments = arguments.map(List::copyOf);
	}
}
