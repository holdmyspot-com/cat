package com.holdmyspot.cat.common.installer.activation;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The outcome of running the harness program.
 *
 * @param exitCode the exit status
 * @param output   standard output and standard error, interleaved, as UTF-8 text
 */
public record CommandResult(int exitCode, String output)
{
	/**
	 * Creates a result.
	 *
	 * @param exitCode the exit status
	 * @param output   standard output and standard error, interleaved, as UTF-8 text
	 * @throws NullPointerException if {@code output} is null
	 */
	public CommandResult
	{
		requireThat(output, "output").isNotNull();
	}
}
