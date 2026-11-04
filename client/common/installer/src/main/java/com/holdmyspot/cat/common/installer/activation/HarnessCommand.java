package com.holdmyspot.cat.common.installer.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The harness's command-line program as setup recorded it.
 *
 * @param command     the command prefix; the first element is an absolute executable path
 * @param environment the complete environment to run the command with
 */
public record HarnessCommand(List<String> command, Map<String, String> environment)
{
	/**
	 * Creates a command.
	 *
	 * @param command     the command prefix; the first element is an absolute executable path
	 * @param environment the complete environment to run the command with
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code command} is empty or its first element is not an absolute path
	 */
	public HarnessCommand
	{
		requireThat(command, "command").isNotEmpty();
		requireThat(Path.of(command.getFirst()), "command.getFirst()").isAbsolute();
		command = List.copyOf(command);
		environment = Map.copyOf(environment);
	}
}
