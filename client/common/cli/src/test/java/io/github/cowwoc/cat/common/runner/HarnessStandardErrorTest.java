package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessStandardError}.
 */
public final class HarnessStandardErrorTest
{
	/**
	 * Creates a standard-error reader test.
	 */
	public HarnessStandardErrorTest()
	{
	}

	/**
	 * Verifies standard error is drained while the harness process remains open.
	 *
	 * @throws IOException if test stream I/O fails
	 */
	@Test
	public void drainsOpenStream() throws IOException
	{
		try (PipedInputStream input = new PipedInputStream())
		{
			HarnessStandardError standardError;
			try (PipedOutputStream output = new PipedOutputStream(input))
			{
				standardError = new HarnessStandardError(input);
				write(output);
			}
			requireThat(standardError.await(), "standardError").isEqualTo("diagnostic");
		}
	}

	/**
	 * Verifies an unbounded harness diagnostic is drained without retaining more than the supported diagnostic size.
	 *
	 * @throws IOException if test stream I/O fails
	 */
	@Test
	public void boundsLargeDiagnostic() throws IOException
	{
		byte[] diagnostic = new byte[HarnessStandardError.MAXIMUM_DIAGNOSTIC_BYTES + 1];
		HarnessStandardError standardError = new HarnessStandardError(new ByteArrayInputStream(diagnostic));

		String result = standardError.await();

		requireThat(result, "standardError").contains("standard error truncated after " +
			HarnessStandardError.MAXIMUM_DIAGNOSTIC_BYTES + " bytes");
	}

	/**
	 * Verifies a parent process's stderr consumer completes after its forked child process closes stderr.
	 *
	 * @throws IOException if test stream I/O fails
	 */
	@Test
	public void parentProcessReadsChildStderrEof() throws IOException
	{
		try (PipedInputStream parentChildStderr = new PipedInputStream())
		{
			HarnessStandardError parentStderrConsumer;
			// This output scope simulates the forked child process owning stderr.
			try (PipedOutputStream childStderr = new PipedOutputStream(parentChildStderr))
			{
				parentStderrConsumer = new HarnessStandardError(parentChildStderr);
				childStderr.flush();
			}

			// Closing the child stderr scope delivers EOF to the parent consumer before it waits for the reader to finish.
			// PipedInputStream.close() would not wake the parent reader while it is blocked in read().
			requireThat(parentStderrConsumer.await(), "parentStderrConsumer").isEmpty();
		}
	}

	/**
	 * Writes and closes one connected output stream.
	 *
	 * @param output the connected output stream
	 * @throws IOException if test stream I/O fails
	 */
	private static void write(PipedOutputStream output) throws IOException
	{
		assert output != null;
		output.write("diagnostic".getBytes(UTF_8));
	}
}
