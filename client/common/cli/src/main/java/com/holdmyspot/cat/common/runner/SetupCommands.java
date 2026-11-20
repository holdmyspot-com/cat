package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.util.List;

/**
 * Runs the harness commands that set up a nested session's isolated home before the harness starts, such as the
 * commands that install a plugin.
 */
@FunctionalInterface
public interface SetupCommands
{
	/**
	 * Runs a setup command to completion.
	 *
	 * @param command the command and its arguments
	 * @param step the name of the setup step, which the nested session's log records
	 * @throws IOException if the command cannot start or fails
	 */
	void execute(List<String> command, String step) throws IOException;
}
