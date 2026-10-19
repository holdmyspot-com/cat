package com.holdmyspot.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The runner's request stream, which the session driver feeds one line at a time.
 * <p>
 * Reading blocks until the driver sends a line or ends the stream, so the runner receives each turn only when the
 * driver sends it.
 * <p>
 * <b>Thread Safety:</b> {@link #send(String)} and {@link #end()} may be called from any thread. A single thread
 * reads.
 */
final class LineFeed extends Reader
{
	private final BlockingQueue<Optional<String>> lines = new LinkedBlockingQueue<>();
	private String current = "";
	private int position;
	private boolean ended;

	/**
	 * Creates a feed that holds no lines.
	 */
	LineFeed()
	{
	}

	/**
	 * Makes a line available to the reader.
	 *
	 * @param line the line, without a terminator
	 * @throws NullPointerException if {@code line} is null
	 */
	void send(String line)
	{
		requireThat(line, "line").isNotNull();
		lines.add(Optional.of(line + "\n"));
	}

	/**
	 * Ends the stream after the lines that were already sent.
	 */
	void end()
	{
		lines.add(Optional.empty());
	}

	@Override
	public int read(char[] buffer, int offset, int length) throws IOException
	{
		if (length == 0)
			return 0;
		while (!ended && position == current.length())
		{
			try
			{
				Optional<String> next = lines.take();
				if (next.isEmpty())
					ended = true;
				else
					current = next.get();
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while waiting for the next request");
			}
			position = 0;
		}
		if (ended)
			return -1;
		int count = Math.min(length, current.length() - position);
		current.getChars(position, position + count, buffer, offset);
		position += count;
		return count;
	}

	@Override
	public void close()
	{
	}
}
