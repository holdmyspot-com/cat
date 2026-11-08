package com.holdmyspot.cat.common.runner.launcher.client;

import com.holdmyspot.cat.common.runner.TurnSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Reads the records that a sender streams as turns on a thread of its own, so that waiting for the next record never
 * holds back the copy of the child's records that the next record may depend on.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class TurnFeed implements TurnSource
{
	/**
	 * The records read and not yet taken, blank lines left out.
	 */
	private final BlockingQueue<String> records = new LinkedBlockingQueue<>();
	/**
	 * The thread that reads the records.
	 */
	private final Thread reader;
	/**
	 * Indicates whether the input ended.
	 */
	private volatile boolean ended;
	/**
	 * Why the input could not be read, or {@code null}.
	 */
	private volatile IOException failure;

	/**
	 * Starts reading records.
	 *
	 * @param input the records, one per line
	 */
	TurnFeed(Reader input)
	{
		BufferedReader lines = new BufferedReader(input);
		this.reader = Thread.ofPlatform().daemon().name("session-request-turns").start(() -> read(lines));
	}

	/**
	 * Reads records until the input ends, fails or the feed closes.
	 *
	 * @param lines the input
	 */
	private void read(BufferedReader lines)
	{
		try
		{
			for (String line = lines.readLine(); line != null; line = lines.readLine())
			{
				if (!line.isBlank())
					records.add(line);
			}
		}
		catch (IOException e)
		{
			failure = e;
		}
		finally
		{
			ended = true;
		}
	}

	@Override
	public Optional<String> poll() throws IOException
	{
		String record = records.poll();
		if (record == null && failure != null)
			throw new IOException("The records cannot be read", failure);
		return Optional.ofNullable(record);
	}

	@Override
	public boolean ended() throws IOException
	{
		if (!ended || !records.isEmpty())
			return false;
		if (failure != null)
			throw new IOException("The records cannot be read", failure);
		return true;
	}

	@Override
	public void close()
	{
		reader.interrupt();
	}
}
