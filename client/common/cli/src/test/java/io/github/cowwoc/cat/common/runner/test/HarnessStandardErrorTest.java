package io.github.cowwoc.cat.common.runner.test;

import io.github.cowwoc.cat.common.runner.HarnessStandardError;
import io.github.cowwoc.cat.common.secrets.HarnessStandardErrorSecrets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import org.testng.annotations.Test;

import static java.nio.charset.StandardCharsets.UTF_8;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


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
		byte[] diagnostic = new byte[HarnessStandardErrorSecrets.access().maximumDiagnosticBytes() + 1];
		HarnessStandardError standardError = new HarnessStandardError(new ByteArrayInputStream(diagnostic));

		String result = standardError.await();

		requireThat(result, "standardError").contains("standard error truncated after " +
			HarnessStandardErrorSecrets.access().maximumDiagnosticBytes() + " bytes");
	}

	/**
	 * Writes and closes one connected output stream.
	 *
	 * @param output the connected output stream
	 *
	 * @throws IOException if test stream I/O fails
	 */
	private static void write(PipedOutputStream output) throws IOException
	{
		assert output != null;
		output.write("diagnostic".getBytes(UTF_8));
	}
}
