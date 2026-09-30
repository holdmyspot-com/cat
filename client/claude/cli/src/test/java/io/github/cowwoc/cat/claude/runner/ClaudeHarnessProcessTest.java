package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.Test;

/**
 * Tests Claude terminal-result interpretation through the harness process adapter.
 */
public final class ClaudeHarnessProcessTest
{
	/**
	 * Verifies a native API error does not complete the requested turn even when Claude emits a result event.
	 *
	 * @throws IOException if the in-memory protocol streams fail
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource") // The in-memory process has no operating-system handle to terminate.
	public void rejectsNativeApiError() throws IOException
	{
		String event = "{" +
			"\"type\":\"result\",\"subtype\":\"success\",\"terminal_reason\":\"api_error\"," +
			"\"is_error\":false,\"result\":\"API Error: 503 Service temporarily unavailable.\"}";
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, new InMemoryProcess(event));
			HarnessProcessResult result = harness.sendTurn("Read the project instructions");

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.assistantResponse(), "assistantResponse").contains("503");
		}
	}

	/**
	 * Verifies a successful native terminal result still completes the requested turn.
	 *
	 * @throws IOException if the in-memory protocol streams fail
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource") // The in-memory process has no operating-system handle to terminate.
	public void acceptsCompletedNativeTurn() throws IOException
	{
		String event = "{" +
			"\"type\":\"result\",\"subtype\":\"success\",\"terminal_reason\":\"completed\"," +
			"\"is_error\":false,\"result\":\"Done.\"}";
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, new InMemoryProcess(event));
			HarnessProcessResult result = harness.sendTurn("Read the project instructions");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("Done.");
		}
	}

	/**
	 * An omitted native error flag cannot establish successful completion.
	 *
	 * @throws IOException if the in-memory protocol streams fail
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource") // The in-memory process has no operating-system handle to terminate.
	public void rejectsMissingErrorFlag() throws IOException
	{
		String event = "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"Done.\"}";
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, new InMemoryProcess(event));
			HarnessProcessResult result = harness.sendTurn("task");
			requireThat(result.completed(), "missingFlagDoesNotProveCompletion").isFalse();
		}
	}

	/**
	 * Verifies a stop sent while the terminal native event is forwarded does not signal a completed turn.
	 *
	 * @throws IOException if the in-memory protocol streams fail
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource") // The in-memory process has no operating-system handle to terminate.
	public void keepsCompletedTurnOnLateStop() throws IOException
	{
		String event = "{" +
			"\"type\":\"result\",\"subtype\":\"success\",\"terminal_reason\":\"completed\"," +
			"\"is_error\":false,\"result\":\"Done.\"}";
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, new AliveInMemoryProcess(event));
			HarnessProcessResult result = harness.sendTurn("task", _ -> harness.interrupt());
			requireThat(result.outcome(), "outcome").isEqualTo(HarnessProcessResult.Outcome.COMPLETED);
		}
	}

	/**
	 * Verifies a successfully signaled native process is reported as interrupted rather than a semantic failure.
	 *
	 * @throws IOException if native process setup or signaling fails
	 * @throws InterruptedException if waiting for native termination is interrupted
	 * @throws ExecutionException if the adapter fails to observe native termination
	 * @throws TimeoutException if cancellation does not finish promptly
	 */
	@Test
	public void classifiesSignaledExitAsInterrupted() throws IOException, InterruptedException, ExecutionException,
		TimeoutException
	{
		CountDownLatch flushed = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newSingleThreadExecutor();
			Process nativeProcess = new ProcessBuilder("sleep", "20").start();
			HarnessRunnerScope scope = new HarnessRunnerScope();
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, new FlushedProcess(nativeProcess, flushed,
				nativeProcess.getInputStream())))
		{
			Future<HarnessProcessResult> result = executor.submit(() -> harness.sendTurn("task"));
			try
			{
				requireThat(flushed.await(5, TimeUnit.SECONDS), "turnFlushed").isTrue();
				harness.interrupt();
				HarnessProcessResult completion = result.get(5, TimeUnit.SECONDS);
				requireThat(completion.outcome(), "outcome").isEqualTo(HarnessProcessResult.Outcome.INTERRUPTED);
				requireThat(completion.exitCode(), "exitCode").isEqualTo(130);
			}
			finally
			{
				nativeProcess.destroyForcibly();
			}
		}
	}

	/**
	 * Verifies a stop accepted before native input is dispatched applies to that reserved turn.
	 *
	 * @throws IOException if native process setup or signaling fails
	 * @throws InterruptedException if waiting for native termination is interrupted
	 * @throws ExecutionException if the adapter fails to observe native termination
	 * @throws TimeoutException if cancellation does not finish promptly
	 */
	@Test
	public void interruptsReservedTurn() throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (ExecutorService executor = Executors.newSingleThreadExecutor();
			Process nativeProcess = new ProcessBuilder("sleep", "20").start();
			HarnessRunnerScope scope = new HarnessRunnerScope();
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, nativeProcess))
		{
			harness.reserveTurn();
			harness.interrupt();
			Future<HarnessProcessResult> result = executor.submit(() -> harness.sendTurn("task"));
			try
			{
				HarnessProcessResult completion = result.get(5, TimeUnit.SECONDS);
				requireThat(completion.outcome(), "outcome").isEqualTo(HarnessProcessResult.Outcome.INTERRUPTED);
				requireThat(completion.exitCode(), "exitCode").isEqualTo(130);
			}
			finally
			{
				nativeProcess.destroyForcibly();
			}
		}
	}

	/**
	 * Verifies Claude's aborted-streaming result acknowledges an interruption sent for the reserved turn.
	 *
	 * @throws IOException if native process setup or signaling fails
	 */
	@Test
	public void classifiesSignaledAbortedStreamingResult() throws IOException
	{
		String event = """
			{"type":"result","subtype":"error_during_execution","terminal_reason":"aborted_streaming","is_error":true}
			""";
		CountDownLatch flushed = new CountDownLatch(1);
		try (Process nativeProcess = new ProcessBuilder("sleep", "20").start();
			HarnessRunnerScope scope = new HarnessRunnerScope();
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, new FlushedProcess(nativeProcess, flushed,
				new ByteArrayInputStream(event.getBytes(UTF_8)))))
		{
			harness.reserveTurn();
			harness.interrupt();
			HarnessProcessResult result = harness.sendTurn("task");

			requireThat(result.outcome(), "outcome").isEqualTo(HarnessProcessResult.Outcome.INTERRUPTED);
		}
	}

	/**
	 * Verifies an aborted-streaming result without this adapter's signal remains a native failure.
	 *
	 * @throws IOException if the in-memory protocol streams fail
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource") // The in-memory process has no operating-system handle to terminate.
	public void rejectsUnsignaledAbortedStreamingResult() throws IOException
	{
		String event = """
			{"type":"result","subtype":"error_during_execution","terminal_reason":"aborted_streaming","is_error":true}
			""";
		try (HarnessRunnerScope scope = new HarnessRunnerScope())
		{
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, new InMemoryProcess(event));
			HarnessProcessResult result = harness.sendTurn("task");

			requireThat(result.outcome(), "outcome").isEqualTo(HarnessProcessResult.Outcome.FAILED);
		}
	}

	/**
	 * Verifies exit 130 without this adapter's acknowledged signal remains a native failure.
	 *
	 * @throws IOException if native process setup or protocol I/O fails
	 */
	@Test
	public void rejectsUnsignaledExit130() throws IOException
	{
		try (Process nativeProcess = new ProcessBuilder("sh", "-c", "read line; exit 130").start();
			HarnessRunnerScope scope = new HarnessRunnerScope();
			ClaudeHarnessProcess harness = new ClaudeHarnessProcess(scope, nativeProcess))
		{
			HarnessProcessResult result = harness.sendTurn("task");
			requireThat(result.outcome(), "outcome").isEqualTo(HarnessProcessResult.Outcome.FAILED);
			requireThat(result.exitCode(), "exitCode").isEqualTo(130);
		}
	}

	/**
	 * Delegates a real process's signal lifecycle while controlling its native event stream and input flush.
	 */
	private static final class FlushedProcess extends Process
	{
		private final Process delegate;
		private final OutputStream input;
		private final InputStream output;

		/**
		 * Wraps a real process and signals after its turn input is flushed.
		 *
		 * @param delegate the native process
		 * @param flushed signals after input reaches the native process
		 * @param output the native events that the adapter observes
		 */
		private FlushedProcess(Process delegate, CountDownLatch flushed, InputStream output)
		{
			this.delegate = delegate;
			this.output = output;
			input = new FilterOutputStream(delegate.getOutputStream())
			{
				@Override
				public void flush() throws IOException
				{
					super.flush();
					flushed.countDown();
				}
			};
		}

		@Override
		public OutputStream getOutputStream()
		{
			return input;
		}

		@Override
		public InputStream getInputStream()
		{
			return output;
		}

		@Override
		public InputStream getErrorStream()
		{
			return delegate.getErrorStream();
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			return delegate.waitFor();
		}

		@Override
		public int exitValue()
		{
			return delegate.exitValue();
		}

		@Override
		public void destroy()
		{
			delegate.destroy();
		}

		@Override
		public boolean isAlive()
		{
			return delegate.isAlive();
		}

		@Override
		public long pid()
		{
			return delegate.pid();
		}

		@Override
		public ProcessHandle toHandle()
		{
			return delegate.toHandle();
		}
	}

	/**
	 * Represents only Claude's already-emitted protocol stream for an in-process adapter check.
	 */
	private static class InMemoryProcess extends Process
	{
		private final ByteArrayOutputStream input = new ByteArrayOutputStream();
		private final ByteArrayInputStream output;

		/**
		 * Creates a process facade with one already-emitted native event.
		 *
		 * @param nativeEvent the native protocol event
		 */
		private InMemoryProcess(String nativeEvent)
		{
			output = new ByteArrayInputStream((nativeEvent + "\n").getBytes(UTF_8));
		}

		@Override
		public OutputStream getOutputStream()
		{
			return input;
		}

		@Override
		public InputStream getInputStream()
		{
			return output;
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor()
		{
			return 0;
		}

		@Override
		public int exitValue()
		{
			return 0;
		}

		@Override
		public void destroy()
		{
		}
	}

	/**
	 * Keeps a fake native process alive to expose an inappropriate post-result signal request.
	 */
	private static final class AliveInMemoryProcess extends InMemoryProcess
	{
		/**
		 * Creates a still-alive fake process with one terminal event.
		 *
		 * @param nativeEvent the terminal native event
		 */
		private AliveInMemoryProcess(String nativeEvent)
		{
			super(nativeEvent);
		}

		@Override
		public boolean isAlive()
		{
			return true;
		}
	}
}
