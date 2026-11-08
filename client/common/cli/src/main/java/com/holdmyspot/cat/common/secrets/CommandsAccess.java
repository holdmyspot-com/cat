package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.optimizer.cli.CommandResult;
import com.holdmyspot.cat.common.optimizer.cli.Commands;

/**
 * Exposes how {@link Commands} renders a result to the module's tests.
 */
@FunctionalInterface
public interface CommandsAccess
{
	/**
	 * Renders a result as the JSON text that a command prints for it.
	 *
	 * @param commands the commands, whose mapper and time zone the text uses
	 * @param result   the result
	 * @return the JSON text, on one line
	 * @throws NullPointerException if any argument is null
	 */
	String render(Commands commands, CommandResult result);
}
