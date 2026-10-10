package io.github.cowwoc.cat.common.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import io.github.cowwoc.pouch10.core.WrappedCheckedException;
import io.github.cowwoc.cat.common.secrets.HarnessStandardErrorAccess;
import io.github.cowwoc.cat.common.secrets.HarnessStandardErrorSecrets;

import static java.nio.charset.StandardCharsets.UTF_8;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Drains a harness process's standard-error stream without blocking its protocol stream.
 */
public final class HarnessStandardError
{
	static
	{
		HarnessStandardErrorSecrets.setAccess(new HarnessStandardErrorAccess()
		{
			@Override
			public int maximumDiagnosticBytes()
			{
				return MAXIMUM_DIAGNOSTIC_BYTES;
			}
		});
	}

	static final int MAXIMUM_DIAGNOSTIC_BYTES = 1_048_576;

	private final InputStream input;
	private final ByteArrayOutputStream content = new ByteArrayOutputStream();
	private Throwable failure;
	private boolean truncated;
	private volatile boolean closed;
	private final Thread reader;

	/**
	 * Begins draining a standard-error stream.
	 *
	 * @param input the process standard-error stream
	 */
	public HarnessStandardError(InputStream input)
	{
		requireThat(input, "input").isNotNull();
		this.input = input;
		reader = Thread.ofVirtual().uncaughtExceptionHandler((_, exception) -> failure = exception).start(
			WrappedCheckedException.wrap(() -> read(input)));
	}

	/**
	 * Closes the diagnostic pipe when the owning runner has terminated the harness process and its descendants.
	 *
	 * @throws IOException if the pipe cannot be closed
	 */
	public void close() throws IOException
	{
		closed = true;
		input.close();
	}

	/**
	 * Waits for the stream to close and returns the retained diagnostic text.
	 *
	 * @return the captured standard-error text
	 *
	 * @throws IOException if reading the stream fails or this thread is interrupted
	 */
	public String await() throws IOException
	{
		try
		{
			reader.join();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while reading harness standard error", e);
		}
		Throwable exception = failure;
		if (exception != null)
		{
			Throwable cause = exception.getCause();
			if (cause instanceof IOException ioException)
				throw new IOException("Unable to read harness standard error", ioException);
			throw new IOException("Unable to read harness standard error", exception);
		}
		String result = content.toString(UTF_8);
		if (truncated)
			return result + "\n[standard error truncated after " + MAXIMUM_DIAGNOSTIC_BYTES + " bytes]";
		return result;
	}

	/**
	 * Drains the stream until its process closes it.
	 *
	 * @param input the standard-error stream
	 */
	private void read(InputStream input) throws IOException
	{
		try (input)
		{
			byte[] buffer = new byte[8192];
			int read = input.read(buffer);
			while (read != -1)
			{
				int retained = Math.min(read, MAXIMUM_DIAGNOSTIC_BYTES - content.size());
				content.write(buffer, 0, retained);
				if (retained < read)
					truncated = true;
				read = input.read(buffer);
			}
		}
		catch (IOException e)
		{
			if (!closed)
				throw e;
		}
	}
}
