package io.github.cowwoc.cat.codex.runner;

import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Tests native turn-control ordering at the Codex app-server process boundary.
 */
public final class CodexAppServerProcessTest
{
	/**
	 * A named-profile thread receives the runtime roots selected by its caller, not the launch directory as a grant.
	 *
	 * @throws IOException if the scripted native protocol or process fails
	 */
	@Test
	public void forwardsActiveWorkspaceRoots() throws IOException
	{
		Path worktree = Path.of("/tmp/cat-codex-active-worktree");
		Path allowed = worktree.resolve("allowed.txt");
		try (ScriptedProcess script = new ScriptedProcess(TerminalMode.COMPLETED);
			CodexAppServerProcess process = new CodexAppServerProcess(script, new HarnessRunnerScope(), false))
		{
			process.initialize(worktree, "", "", "", "cat-active-worktree", List.of(allowed));
			process.reserveTurn();
			process.sendTurn("");

			requireThat(script.threadStartRequest, "threadStartRequest").
				contains("\"cwd\":\"" + worktree + "\"").
				contains("\"permissions\":\"cat-active-worktree\"").
				contains("\"runtimeWorkspaceRoots\":[\"" + allowed + "\"]").
				doesNotContain("\"sandbox\"");
			requireThat(script.failure, "scriptFailure").isNull();
		}
	}

	/**
	 * Verifies an early stop waits for the native turn-start notification before sending turn/interrupt.
	 *
	 * @throws IOException if the protocol pipes fail
	 * @throws InterruptedException if the scripted native turn wait is interrupted
	 * @throws ExecutionException if the native turn fails
	 * @throws TimeoutException if the native turn does not finish
	 */
	@Test
	public void queuesStopUntilNativeTurnStarts() throws IOException, InterruptedException, ExecutionException,
		TimeoutException
	{
		try (ExecutorService executor = Executors.newSingleThreadExecutor();
			ScriptedProcess script = new ScriptedProcess(TerminalMode.INTERRUPTED);
			CodexAppServerProcess process = new CodexAppServerProcess(script, new HarnessRunnerScope(), false))
		{
			process.initialize(Path.of("/tmp/cat-codex-protocol-test"), "", "", "low");
			process.reserveTurn();
			Future<HarnessProcessResult> turn = executor.submit(() -> process.sendTurn("task"));
			try
			{
				requireThat(script.turnStartRequestReceived.await(2, TimeUnit.SECONDS), "startRequestReceived").isTrue();
				process.interrupt();
			}
			finally
			{
				script.releaseTurnStarted.countDown();
			}
			HarnessProcessResult result = turn.get(5, TimeUnit.SECONDS);

			requireThat(script.interruptBeforeNotification, "interruptBeforeNotification").isFalse();
			requireThat(script.interruptRequest, "interruptRequest").contains("\"method\":\"turn/interrupt\"").
				contains("\"turnId\":\"turn-1\"");
			requireThat(script.turnStartRequest, "turnStartRequest").contains("\"effort\":\"low\"");
			requireThat(result.outcome(), "outcome").isEqualTo(HarnessProcessResult.Outcome.INTERRUPTED);
			requireThat(script.failure, "scriptFailure").isNull();
		}
	}

	/**
	 * A completed native final answer becomes the runner's assistant response, including for an empty user turn.
	 *
	 * @throws IOException if the scripted native protocol or process fails
	 */
	@Test
	public void retainsCompletedFinalAnswer() throws IOException
	{
		try (ScriptedProcess script = new ScriptedProcess(TerminalMode.COMPLETED);
			CodexAppServerProcess process = new CodexAppServerProcess(script, new HarnessRunnerScope(), false))
		{
			process.initialize(Path.of("/tmp/cat-codex-protocol-test"), "", "", "low");
			process.reserveTurn();
			HarnessProcessResult result = process.sendTurn("");

			requireThat(script.turnStartRequest, "turnStartRequest").contains("\"text\":\"\"");
			requireThat(result.outcome(), "outcome").isEqualTo(HarnessProcessResult.Outcome.COMPLETED);
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("final response");
			requireThat(script.failure, "scriptFailure").isNull();
		}
	}

	/**
	 * The terminal native status emitted by the scripted app-server.
	 */
	private enum TerminalMode
	{
		COMPLETED,
		INTERRUPTED
	}

	/**
	 * Simulates the app-server protocol while exposing the response/notification boundary.
	 */
	private static final class ScriptedProcess extends Process
	{
		private final TerminalMode mode;
		private final PipedInputStream nativeRequests = new PipedInputStream();
		private final PipedOutputStream runnerRequests;
		private final PipedInputStream runnerResponses = new PipedInputStream();
		private final PipedOutputStream nativeResponses;
		private final CountDownLatch turnStartRequestReceived = new CountDownLatch(1);
		private final CountDownLatch releaseTurnStarted = new CountDownLatch(1);
		private final Thread server;
		private volatile boolean interruptBeforeNotification;
		private volatile String interruptRequest;
		private volatile String threadStartRequest;
		private volatile String turnStartRequest;
		private volatile Throwable failure;

		/**
		 * Connects the runner and simulated native app-server pipes.
		 *
		 * @param mode the terminal native status to emit
		 * @throws IOException if either pipe cannot be connected
		 */
		private ScriptedProcess(TerminalMode mode) throws IOException
		{
			this.mode = mode;
			runnerRequests = new PipedOutputStream(nativeRequests);
			nativeResponses = new PipedOutputStream(runnerResponses);
			server = Thread.ofVirtual().start(this::serve);
		}

		/**
		 * Sends the scripted native responses and records the exact interrupt ordering.
		 */
		private void serve()
		{
			try (BufferedReader requests = new BufferedReader(new InputStreamReader(nativeRequests, UTF_8));
				BufferedWriter responses = new BufferedWriter(new OutputStreamWriter(nativeResponses, UTF_8)))
			{
				requireRequest(requests, "\"method\":\"initialize\"");
				respond(responses, "{\"id\":1,\"result\":{\"serverInfo\":{\"version\":\"test\"}}}");
				requireRequest(requests, "\"method\":\"initialized\"");
				threadStartRequest = requireRequest(requests, "\"method\":\"thread/start\"");
				respond(responses, "{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread-1\"}}}");
				turnStartRequest = requireRequest(requests, "\"method\":\"turn/start\"");
				turnStartRequestReceived.countDown();
				if (mode == TerminalMode.COMPLETED)
				{
					respond(responses, "{\"id\":3,\"result\":{\"turn\":{\"id\":\"turn-1\"}}}");
					respond(responses, "{\"method\":\"turn/started\",\"params\":{\"threadId\":\"thread-1\"," +
						"\"turn\":{\"id\":\"turn-1\"}}}");
					respond(responses, "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\"," +
						"\"turn\":{\"id\":\"turn-1\",\"status\":\"completed\",\"items\":[" +
						"{\"type\":\"agentMessage\",\"phase\":\"commentary\",\"text\":\"thinking\"}," +
						"{\"type\":\"agentMessage\",\"phase\":\"final_answer\",\"text\":\"final response\"}]}}}");
					while (requests.readLine() != null)
					{
						// The runner closes native input after the terminal turn.
					}
					return;
				}
				releaseTurnStarted.await();
				interruptBeforeNotification = requests.ready();
				respond(responses, "{\"id\":3,\"result\":{\"turn\":{\"id\":\"turn-1\"}}}");
				respond(responses, "{\"method\":\"turn/started\",\"params\":{\"threadId\":\"thread-1\"," +
					"\"turn\":{\"id\":\"turn-1\"}}}");
				interruptRequest = requireRequest(requests, "\"method\":\"turn/interrupt\"");
				respond(responses, "{\"id\":4,\"result\":{}}");
				respond(responses, "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-1\"," +
					"\"turn\":{\"id\":\"turn-1\",\"status\":\"interrupted\",\"error\":null}}}");
				while (requests.readLine() != null)
				{
					// The runner closes its native input after the terminal turn.
				}
			}
			catch (IOException | InterruptedException | RuntimeException exception)
			{
				failure = exception;
			}
		}

		/**
		 * Reads one request of the expected protocol kind.
		 *
		 * @param requests the runner's requests
		 * @param expected the expected method fragment
		 * @return the complete request line
		 * @throws IOException if the request is absent or has another method
		 */
		private static String requireRequest(BufferedReader requests, String expected) throws IOException
		{
			String request = requests.readLine();
			if (request == null || !request.contains(expected))
				throw new IOException("Expected native request " + expected + ", observed " + request);
			return request;
		}

		/**
		 * Sends one native JSONL response.
		 *
		 * @param responses the native output
		 * @param message the response line
		 * @throws IOException if the output pipe fails
		 */
		private static void respond(BufferedWriter responses, String message) throws IOException
		{
			responses.write(message);
			responses.newLine();
			responses.flush();
		}

		@Override
		public OutputStream getOutputStream()
		{
			return runnerRequests;
		}

		@Override
		public InputStream getInputStream()
		{
			return runnerResponses;
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			server.join();
			return 0;
		}

		@Override
		public int exitValue()
		{
			if (server.isAlive())
				throw new IllegalThreadStateException("Scripted app-server is still running");
			return 0;
		}

		@Override
		public void destroy()
		{
			try
			{
				close();
			}
			catch (IOException exception)
			{
				failure = exception;
			}
		}

		@Override
		public Process destroyForcibly()
		{
			destroy();
			return this;
		}

		@Override
		public boolean isAlive()
		{
			return server.isAlive();
		}

		@Override
		public void close() throws IOException
		{
			releaseTurnStarted.countDown();
			runnerRequests.close();
			nativeResponses.close();
		}
	}
}
