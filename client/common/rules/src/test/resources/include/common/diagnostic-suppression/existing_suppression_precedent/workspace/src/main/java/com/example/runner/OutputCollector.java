package com.example.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Collects everything that {@code {{command}}} writes to its standard output.
 */
public final class OutputCollector
{
	private final ByteArrayOutputStream output = new ByteArrayOutputStream();
	private Thread reader;

	/**
	 * Starts a thread that copies the output of the command.
	 *
	 * @param process the started command
	 */
	public void start(Process process)
	{
		InputStream input = process.getInputStream();
		reader = Thread.ofVirtual().start(() ->
		{
			try
			{
				input.transferTo(output);
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		});
	}

	/**
	 * Waits until the command closes its output.
	 *
	 * @return everything that the command wrote
	 * @throws InterruptedException if the thread is interrupted while waiting
	 */
	public String await() throws InterruptedException
	{
		reader.join();
		return output.toString(StandardCharsets.UTF_8);
	}
}
