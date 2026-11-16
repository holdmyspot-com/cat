package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.runner.HarnessStandardError;
import com.holdmyspot.cat.common.secrets.HarnessStandardErrorSecrets;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
	 * Verifies a harness producer can write and flush its complete diagnostic through a one-byte stderr pipe while the
	 * reader remains open. The producer thread represents the harness process writing its standard-error stream and stays
	 * alive until the test closes that stream.
	 *
	 * @throws IOException          if test stream I/O fails
	 * @throws InterruptedException if cleanup of the harness producer is interrupted
	 * @throws ExecutionException   if writing the harness diagnostic fails
	 * @throws TimeoutException     if the harness producer does not finish within five seconds
	 */
	@Test(timeOut = 10_000)
	public void drainsOpenStream() throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (PipedInputStream input = new PipedInputStream(1))
		{
			CompletableFuture<Boolean> diagnosticWriteCompleted = new CompletableFuture<>();
			CountDownLatch harnessMayExit = new CountDownLatch(1);
			HarnessStandardError standardError = null;
			Thread harnessProducer = null;

			try
			{
				try (PipedOutputStream harnessOutput = new PipedOutputStream(input))
				{
					standardError = new HarnessStandardError(input);
					harnessProducer = Thread.startVirtualThread(() ->
					{
						try
						{
						write(harnessOutput);
						harnessOutput.flush();
						diagnosticWriteCompleted.complete(true);
						harnessMayExit.await();
					}
						catch (IOException e)
						{
							diagnosticWriteCompleted.completeExceptionally(e);
						}
						catch (InterruptedException e)
						{
							diagnosticWriteCompleted.completeExceptionally(e);
							Thread.currentThread().interrupt();
						}
					});

					requireThat(diagnosticWriteCompleted.get(5, TimeUnit.SECONDS), "diagnosticWriteCompleted").isTrue();
					// Closing this scope represents the harness closing stderr after writing its complete diagnostic.
				}
				harnessMayExit.countDown();
				requireThat(standardError.await(), "standardError").isEqualTo("diagnostic");
			}
			finally
			{
				harnessMayExit.countDown();
				try
				{
					if (standardError != null)
						standardError.close();
				}
				finally
				{
					if (harnessProducer != null)
					{
						if (harnessProducer.isAlive())
						{
							harnessProducer.interrupt();
							harnessProducer.join(5_000);
						}
						requireThat(harnessProducer.isAlive(), "harnessProducer").isFalse();
					}
				}
			}
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
		int maximumDiagnosticBytes = HarnessStandardErrorSecrets.getHarnessStandardErrorAccess().
			maximumDiagnosticBytes();
		String prefix = "a".repeat(maximumDiagnosticBytes);
		String discardedTail = "discarded diagnostic tail";
		byte[] diagnostic = (prefix + discardedTail).getBytes(UTF_8);
		HarnessStandardError standardError = new HarnessStandardError(new ByteArrayInputStream(diagnostic));

		String result = standardError.await();

		requireThat(result, "standardError").isEqualTo(prefix + "\n[standard error truncated after " +
			maximumDiagnosticBytes + " bytes]");
		requireThat(result, "standardError").contains("standard error truncated after " + maximumDiagnosticBytes +
			" bytes");
	}

	/**
	 * Writes diagnostic bytes to the harness's stderr pipe without closing it.
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
