package com.holdmyspot.cat.common.installer.activation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Runs the harness program that setup recorded. The installer core implements it.
 */
public interface HarnessCli
{
	/**
	 * Returns the harness program that this runner runs.
	 *
	 * @return the program's path, as setup recorded it
	 */
	Path program();

	/**
	 * Runs the recorded command followed by {@code arguments}, with exactly the recorded environment and the harness
	 * home as working directory, without a shell and with standard input closed, and waits for it to exit.
	 *
	 * @param arguments the arguments after the recorded command prefix
	 * @return the exit status and output
	 * @throws NullPointerException if {@code arguments} is null
	 * @throws IOException          if the program cannot be started or its output cannot be read
	 */
	CommandResult run(List<String> arguments) throws IOException;

	/**
	 * Starts the recorded command followed by {@code arguments}, with exactly the recorded environment and the harness
	 * home as working directory, without a shell, for a conversation through its standard input and output. Its
	 * diagnostic output is discarded. The caller owns the process: it closes the process's input when the conversation
	 * ends and destroys the process if it does not exit.
	 *
	 * @param arguments the arguments after the recorded command prefix
	 * @return the running process
	 * @throws NullPointerException if {@code arguments} is null
	 * @throws IOException          if the program cannot be started
	 */
	Process start(List<String> arguments) throws IOException;
}
