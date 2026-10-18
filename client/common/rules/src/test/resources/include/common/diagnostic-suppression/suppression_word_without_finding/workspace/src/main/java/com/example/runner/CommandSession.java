package com.example.runner;

import java.io.IOException;

/**
 * A running command that the session stops when it closes.
 */
public final class CommandSession implements AutoCloseable
{
	private final Process process;

	/**
	 * Creates a session that {{typo}} ownership of the started command and closes it when the session closes.
	 *
	 * @param process the started command
	 */
	@SuppressWarnings("PMD.CloseResource")
	public CommandSession(Process process)
	{
		this.process = process;
	}

	@Override
	public void close() throws IOException
	{
		process.destroy();
		process.close();
	}
}
