package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.fakes.FakeHarnessCli;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how {@link CodexProcess} runs, interrupts and reports a turn. Each test plays the Codex app-server
 * with a {@link FakeHarnessCli} that answers JSON-RPC requests with notifications shaped like those codex-cli
 * 0.159.1 printed for a root thread with child threads.
 */
public final class CodexProcessTest
{
	private static final String ROOT_THREAD = "thread-root";
	private static final String CHILD_THREAD = "thread-child";
	private static final String ROOT_TURN = "turn-root";
	private static final Path PROJECT = Path.of("/project");
	/**
	 * Request lines that the tests play no part in.
	 */
	private static final LongFunction<List<String>> NO_ANSWER = _ -> List.of();

	/**
	 * Creates the test fixture.
	 */
	public CodexProcessTest()
	{
	}

	/**
	 * Verifies the turn's assistant response is the text of the root thread's last completed {@code agentMessage},
	 * not a child thread's message that completed later.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void repliesWithLastRootAgentMessage() throws IOException
	{
		// Requirement: R-E13
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, "I will read the evidence."),
			agentMessage(CHILD_THREAD, "Child finding."),
			agentMessage(ROOT_THREAD, "{\"verdict\":\"fail\"}"),
			agentMessage(CHILD_THREAD, "Child afterthought."),
			turnCompleted(CHILD_THREAD, "turn-child", "completed"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (CodexProcess process = server.start())
		{
			HarnessProcessResult result = process.sendTurn("Judge the evidence.");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("{\"verdict\":\"fail\"}");
		}
	}

	/**
	 * Verifies an interrupt during a turn sends {@code turn/interrupt} for the active root turn, and the interrupted
	 * turn ends as a completed bridge turn with the root's last message.
	 *
	 * @throws IOException          if the turn fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 * @throws ExecutionException   if the turn fails on its own thread
	 * @throws TimeoutException     if the turn does not end after the interruption
	 */
	@Test(timeOut = 10_000)
	public void interruptsActiveTurn() throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E10
		AppServer server = new AppServer(id -> List.of(turnStarted(id), agentMessage(ROOT_THREAD, "Working on it.")),
			id -> List.of(response(id), turnCompleted(ROOT_THREAD, ROOT_TURN, "interrupted")));
		CountDownLatch working = new CountDownLatch(1);
		try (ExecutorService turnThread = Executors.newSingleThreadExecutor();
			CodexProcess process = server.start())
		{
			Future<HarnessProcessResult> turn = turnThread.submit(() -> process.sendTurn("Do the task.", line ->
			{
				if (line.contains("Working on it."))
					working.countDown();
			}));
			working.await();

			process.interrupt();
			HarnessProcessResult result = turn.get(5, TimeUnit.SECONDS);

			requireThat(server.interruptRequests(), "interruptRequests").isEqualTo(List.of(ROOT_THREAD + "/" +
				ROOT_TURN));
			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.exitCode(), "exitCode").isEqualTo(0);
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("Working on it.");
		}
	}

	/**
	 * Verifies an interrupt that arrives after the turn was requested but before Codex identified the turn is sent
	 * once Codex identifies it.
	 *
	 * @throws IOException          if the turn fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 * @throws ExecutionException   if the turn fails on its own thread
	 * @throws TimeoutException     if the turn does not end after the interruption
	 */
	@Test(timeOut = 10_000)
	public void interruptsTurnBeforeItIsIdentified()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E10
		AppServer server = new AppServer(NO_ANSWER,
			id -> List.of(response(id), turnCompleted(ROOT_THREAD, ROOT_TURN, "interrupted")));
		try (ExecutorService turnThread = Executors.newSingleThreadExecutor();
			CodexProcess process = server.start())
		{
			Future<HarnessProcessResult> turn = turnThread.submit(() -> process.sendTurn("Do the task."));
			long turnRequest = server.awaitTurnRequest();

			process.interrupt();
			requireThat(server.interruptRequests(), "interruptRequestsBeforeIdentification").isEmpty();
			server.process.emit(turnStarted(turnRequest));
			HarnessProcessResult result = turn.get(5, TimeUnit.SECONDS);

			requireThat(server.interruptRequests(), "interruptRequests").isEqualTo(List.of(ROOT_THREAD + "/" +
				ROOT_TURN));
			requireThat(result.completed(), "completed").isTrue();
		}
	}

	/**
	 * Verifies an interrupt that arrives after the turn has ended sends nothing to Codex, and the process still serves
	 * the next turn.
	 *
	 * @throws IOException if a turn or the interrupt fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterTurnEnded() throws IOException
	{
		// Requirement: R-E10
		AppServer server = new AppServer(id -> List.of(turnStarted(id), agentMessage(ROOT_THREAD, "Done."),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (CodexProcess process = server.start())
		{
			process.sendTurn("First task.");

			process.interrupt();

			HarnessProcessResult next = process.sendTurn("Second task.");
			requireThat(server.interruptRequests(), "interruptRequests").isEmpty();
			requireThat(next.assistantResponse(), "assistantResponse").isEqualTo("Done.");
		}
	}

	/**
	 * Verifies that a turn which ends because Codex closed its output waits for the operating system to report the
	 * exit, instead of reading an exit status that does not exist yet.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void waitsForExitAfterOutputEnds() throws IOException
	{
		// Requirement: R-E13
		AppServer server = new AppServer(id -> List.of(turnStarted(id)), NO_ANSWER).closingOutputAfterTurnStart();
		try (CodexProcess process = server.start())
		{
			HarnessProcessResult result = process.sendTurn("Judge the evidence.");

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.standardError(), "standardError").contains("ended before turn completion");
		}
	}

	/**
	 * Returns Codex's answer to a {@code turn/start} request followed by the root turn's start notification.
	 *
	 * @param requestId the {@code turn/start} request's identifier
	 * @return the lines Codex prints
	 */
	private static String turnStarted(long requestId)
	{
		return "{\"id\":" + requestId + ",\"result\":{\"turn\":{\"id\":\"" + ROOT_TURN + "\",\"items\":[]," +
			"\"status\":\"inProgress\",\"error\":null}}}\n{\"method\":\"turn/started\",\"params\":{\"threadId\":\"" +
			ROOT_THREAD + "\",\"turn\":{\"id\":\"" + ROOT_TURN + "\",\"status\":\"inProgress\"}}}";
	}

	/**
	 * Returns Codex's empty answer to a request.
	 *
	 * @param requestId the request's identifier
	 * @return the response line
	 */
	private static String response(long requestId)
	{
		return "{\"id\":" + requestId + ",\"result\":{}}";
	}

	/**
	 * Returns the notification that a thread completed an assistant message.
	 *
	 * @param threadId the thread that wrote the message
	 * @param text the message
	 * @return the notification line
	 */
	private static String agentMessage(String threadId, String text)
	{
		return "{\"method\":\"item/completed\",\"params\":{\"item\":{\"type\":\"agentMessage\",\"id\":\"msg\"," +
			"\"text\":" + JsonMapper.builder().build().writeValueAsString(text) + ",\"phase\":\"final_answer\"}," +
			"\"threadId\":\"" + threadId + "\",\"turnId\":\"turn\"}}";
	}

	/**
	 * Returns the notification that a thread's turn ended.
	 *
	 * @param threadId the thread
	 * @param turnId the turn
	 * @param status how the turn ended
	 * @return the notification line
	 */
	private static String turnCompleted(String threadId, String turnId, String status)
	{
		return "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + threadId + "\",\"turn\":{\"id\":\"" +
			turnId + "\",\"items\":[],\"status\":\"" + status + "\",\"error\":null}}}";
	}

	/**
	 * Plays the Codex app-server: answers the start-up requests, then answers each {@code turn/start} and
	 * {@code turn/interrupt} request with the lines the test supplies.
	 */
	private static final class AppServer
	{
		private final JsonMapper json = JsonMapper.builder().build();
		private final LongFunction<List<String>> onTurnStart;
		private final LongFunction<List<String>> onTurnInterrupt;
		private final FakeHarnessCli process = new FakeHarnessCli(this::answer);
		private final CountDownLatch turnRequested = new CountDownLatch(1);
		private volatile long turnRequestId;
		private volatile boolean closesOutputAfterTurnStart;

		/**
		 * Creates an app-server that has not started.
		 *
		 * @param onTurnStart returns the lines to print for a {@code turn/start} request with the given identifier
		 * @param onTurnInterrupt returns the lines to print for a {@code turn/interrupt} request with the given
		 *   identifier
		 */
		private AppServer(LongFunction<List<String>> onTurnStart, LongFunction<List<String>> onTurnInterrupt)
		{
			this.onTurnStart = onTurnStart;
			this.onTurnInterrupt = onTurnInterrupt;
		}

		/**
		 * Makes the app-server close its output right after it answers {@code turn/start}, without reporting that it
		 * exited until a caller waits for it.
		 *
		 * @return this app-server
		 */
		private AppServer closingOutputAfterTurnStart()
		{
			closesOutputAfterTurnStart = true;
			return this;
		}

		/**
		 * Starts a runner process over this app-server and creates its root thread.
		 *
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start() throws IOException
		{
			CodexProcess result = new CodexProcess(process, new HarnessRunnerScope(), false);
			result.initialize(PROJECT, "");
			return result;
		}

		/**
		 * Waits for the first {@code turn/start} request.
		 *
		 * @return the request's identifier
		 * @throws InterruptedException if the wait is interrupted
		 */
		private long awaitTurnRequest() throws InterruptedException
		{
			turnRequested.await();
			return turnRequestId;
		}

		/**
		 * Returns the {@code turn/interrupt} requests received so far.
		 *
		 * @return each request's thread and turn, as {@code thread/turn}
		 */
		private List<String> interruptRequests()
		{
			return process.requests().stream().map(json::readTree).
				filter(request -> request.path("method").asString("").equals("turn/interrupt")).
				map(request -> request.path("params").path("threadId").asString() + "/" +
					request.path("params").path("turnId").asString()).
				toList();
		}

		/**
		 * Answers one request that the runner wrote.
		 *
		 * @param line the request line
		 */
		private void answer(String line)
		{
			JsonNode request = json.readTree(line);
			long id = request.path("id").asLong(0);
			switch (request.path("method").asString(""))
			{
				case "initialize" -> process.emit(response(id));
				case "thread/start" -> process.emit("{\"id\":" + id + ",\"result\":{\"thread\":{\"id\":\"" +
					ROOT_THREAD + "\"}}}");
				case "turn/start" ->
				{
					turnRequestId = id;
					turnRequested.countDown();
					process.emit(onTurnStart.apply(id).toArray(String[]::new));
					if (closesOutputAfterTurnStart)
						process.closeOutputThenExitWhenAwaited();
				}
				case "turn/interrupt" -> process.emit(onTurnInterrupt.apply(id).toArray(String[]::new));
				default ->
				{
				}
			}
		}
	}
}
