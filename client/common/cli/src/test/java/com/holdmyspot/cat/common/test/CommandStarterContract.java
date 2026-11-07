package com.holdmyspot.cat.common.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.WaitUntil;

import java.io.IOException;

/**
 * Checks that a {@link WaitUntil.CommandStarter} starts the process that runs a command, and that the process delivers
 * the command's exit status, and its standard output and standard error through {@link Process#getInputStream()}.
 */
public final class CommandStarterContract
{
	/**
	 * The command that the starter runs.
	 */
	private static final String COMMAND = "gh run view 1 --exit-status";

	/**
	 * The starter under contract.
	 */
	private final Subject subject;

	/**
	 * Creates checks of one starter.
	 *
	 * @param subject the starter under contract
	 */
	public CommandStarterContract(Subject subject)
	{
		this.subject = subject;
	}

	/**
	 * Verifies that the process of a started command exits with the command's status and delivers what it printed to
	 * its standard output and its standard error.
	 *
	 * @throws IOException          if the command cannot start or its output cannot be read
	 * @throws InterruptedException if the thread is interrupted while it waits for the process
	 */
	public void startsCommand() throws IOException, InterruptedException
	{
		WaitUntil.CommandStarter starter = subject.starter(COMMAND, 8, "conclusion: failure\n", "run 1 failed\n");

		try (Process process = starter.start(COMMAND))
		{
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);

			requireThat(process.waitFor(), "status").isEqualTo(8);
			requireThat(output, "output").contains("conclusion: failure\n").contains("run 1 failed\n");
		}
	}

	/**
	 * The starter under contract.
	 */
	@FunctionalInterface
	public interface Subject
	{
		/**
		 * Returns a starter whose run of a command exits with a status after printing to its standard output and its
		 * standard error.
		 *
		 * @param command the command
		 * @param status  the command's exit status
		 * @param stdout  what the command prints to its standard output
		 * @param stderr  what the command prints to its standard error
		 * @return the starter
		 */
		WaitUntil.CommandStarter starter(String command, int status, String stdout, String stderr);
	}
}
