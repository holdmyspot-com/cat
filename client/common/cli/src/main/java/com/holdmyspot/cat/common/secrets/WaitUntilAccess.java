package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.ExecutableFiles;
import com.holdmyspot.cat.common.WaitUntil;
import com.holdmyspot.cat.common.WaitUntil.CommandStarter;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Exposes how a {@link WaitUntil} measures time and starts its commands to the module's tests.
 */
public interface WaitUntilAccess
{
	/**
	 * Creates a wait that measures its deadline and intervals through a timer.
	 *
	 * @param starter starts the commands
	 * @param timer   measures the deadline and the intervals, and waits for runs to end
	 * @param output  receives the outcome
	 * @param error   receives usage errors
	 * @return the wait
	 * @throws NullPointerException if any argument is null
	 */
	WaitUntil create(CommandStarter starter, WaitUntilTimer timer, PrintStream output, PrintStream error);

	/**
	 * Returns the timer of a wait that {@link WaitUntil#WaitUntil(CommandStarter, PrintStream, PrintStream)} creates,
	 * which reads {@link System#nanoTime()} and waits in real time.
	 *
	 * @return the timer
	 */
	WaitUntilTimer systemTimer();

	/**
	 * Returns the starter that {@link WaitUntil#shellStarter(Function, Path)} returns, starting each shell process
	 * through a launcher instead of the operating system.
	 *
	 * @param shell     returns the program and arguments that run a command in the harness's shell
	 * @param directory the folder that the commands run in
	 * @param launcher  starts the configured shell process
	 * @return the starter
	 * @throws NullPointerException if any argument is null
	 */
	CommandStarter shellStarter(Function<String, List<String>> shell, Path directory,
		ExecutableFiles.ProcessLauncher launcher);
}
