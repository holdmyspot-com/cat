package com.holdmyspot.cat.common.installer.cli.internal;

import java.io.IOException;
import java.util.List;

/**
 * Starts a program that keeps running after the installer's process exits, without waiting for it.
 */
@FunctionalInterface
public interface ProcessStarter
{
	/**
	 * Starts the program.
	 *
	 * @param command the program's absolute path, then its arguments
	 * @throws NullPointerException if {@code command} is null
	 * @throws IOException          if the program cannot be started
	 */
	void start(List<String> command) throws IOException;
}
