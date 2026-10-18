package com.example.runner;

import java.io.IOException;

/**
 * A running command that the session stops when it closes.
 */
public final class CommandSession implements AutoCloseable
{
	private final Process process;

	/**
	 * Creates a session.
	 *
	 * @param process the started command, which the session closes
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
