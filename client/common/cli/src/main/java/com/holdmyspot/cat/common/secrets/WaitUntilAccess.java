package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.WaitUntil;
import com.holdmyspot.cat.common.WaitUntil.CommandStarter;

import java.io.PrintStream;

/**
 * Exposes how a {@link WaitUntil} measures time to the module's tests.
 */
@FunctionalInterface
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
}
