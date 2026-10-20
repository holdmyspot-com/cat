package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A hook that runs before the model acts, at session start or prompt submission, as the harness runs it.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param name        a sentence fragment that names the hook, its event and the file that declares it
 * @param command     the program and its arguments
 * @param environment the variables that the harness sets for the hook, on top of its own environment
 * @param input       the text that the harness writes to the hook's standard input
 * @param timeout     how long the harness lets the hook run
 */
public record HookInvocation(String name, List<String> command, Map<String, String> environment, String input,
	Duration timeout)
{
	/**
	 * Creates a hook invocation.
	 *
	 * @param name        a sentence fragment that names the hook, its event and the file that declares it
	 * @param command     the program and its arguments
	 * @param environment the variables that the harness sets for the hook, on top of its own environment
	 * @param input       the text that the harness writes to the hook's standard input
	 * @param timeout     how long the harness lets the hook run
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code name} is blank, if {@code command} is empty, or if {@code timeout}
	 *                                  is not positive
	 */
	public HookInvocation
	{
		requireThat(name, "name").isNotBlank();
		requireThat(command, "command").isNotEmpty();
		requireThat(environment, "environment").isNotNull();
		requireThat(input, "input").isNotNull();
		requireThat(timeout, "timeout").isGreaterThan(Duration.ZERO);
		command = List.copyOf(command);
		environment = Map.copyOf(environment);
	}
}
