package com.holdmyspot.cat.common.runner.policy;

import java.util.List;
import java.util.Optional;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * What the operating system reports about a running process.
 * <p>
 * A process's start is text that stays the same for as long as the process lives, in every process that reads it,
 * and that a later process with the same identifier does not have. On Linux it is the identifier of the boot without
 * hyphens, the letter {@code t}, and the clock ticks between the boot and the process's start, as the kernel records
 * them; the boot's identifier tells a process apart from one of another boot with the same identifier and ticks.
 * Linux keeps no record of the instant at which a process started: {@link ProcessHandle.Info#startInstant()} adds the
 * ticks to the boot time that the kernel derives from the current wall clock, which moves whenever the wall clock is
 * set, so another process that reads it later can get another instant for the same process. Elsewhere the start is
 * the instant at which the process started, in milliseconds since the epoch, which the kernel records when the
 * process starts.
 *
 * @param processId the process ID
 * @param start     the process's start, or empty if the system does not report it
 * @param command   the program that the process runs, or empty if the system does not report it
 * @param arguments the arguments after the program, or empty if the system does not report them
 */
public record ProcessDescription(long processId, Optional<String> start, Optional<String> command,
	Optional<List<String>> arguments)
{
	/**
	 * The form of a process's start: lower-case letters and digits, so that a launcher's name, which holds it, keeps
	 * the form that every reader of launcher names accepts.
	 */
	public static final String START_PATTERN = "[0-9a-z]+";

	/**
	 * Creates a process.
	 *
	 * @param processId the process ID
	 * @param start     the process's start, or empty if the system does not report it
	 * @param command   the program that the process runs, or empty if the system does not report it
	 * @param arguments the arguments after the program, or empty if the system does not report them
	 * @throws NullPointerException     if any of the arguments are null, or {@code arguments} contains null
	 * @throws IllegalArgumentException if {@code start} does not match {@link #START_PATTERN}
	 */
	public ProcessDescription
	{
		requireThat(start, "start").isNotNull();
		start.ifPresent(value -> requireThat(value, "start").matches(START_PATTERN));
		requireThat(command, "command").isNotNull();
		requireThat(arguments, "arguments").isNotNull();
		arguments = arguments.map(List::copyOf);
	}
}
