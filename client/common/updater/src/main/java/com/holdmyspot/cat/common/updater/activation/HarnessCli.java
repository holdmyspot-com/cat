package com.holdmyspot.cat.common.updater.activation;

import java.io.IOException;
import java.util.List;

/**
 * Runs the harness program that setup recorded. The updater core implements it.
 */
@FunctionalInterface
public interface HarnessCli
{
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
}
