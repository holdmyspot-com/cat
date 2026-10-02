package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.fakes.FakeHarnessCli;
import io.github.cowwoc.cat.common.runner.fakes.TestHarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;

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
 * Tests how {@link CodexProcess} constructs requests and interprets supplied app-server events.
 * {@link FakeHarnessCli} supplies controlled protocol input;
 * these tests do not establish how a live app-server responds.
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
	 * Verifies that the runner selects its profile through the app-server protocol: the model as
	 * {@code thread/start}'s {@code model} and the reasoning effort as each {@code turn/start}'s {@code effort}.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void selectsTheProfileThroughTheAppServer() throws IOException
	{
		// Requirement: R-E17
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, "done"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (CodexProcess process = server.start("gpt-5.5", "xhigh"))
		{
			process.sendTurn("Run the trial.");

			requireThat(server.requests("thread/start").getFirst().path("model").asString(""), "model").
				isEqualTo("gpt-5.5");
			requireThat(server.requests("turn/start").getFirst().path("effort").asString(""), "effort").
				isEqualTo("xhigh");
		}
	}

	/**
	 * Verifies an interrupt during a turn targets the active root turn. Given the supplied {@code interrupted}
	 * completion notification, the bridge classifies the caller-interrupted turn as successful.
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
	 * Verifies an interrupt after a supplied completion notification sends no interrupt request. A subsequent
	 * supplied completion notification is still classified as a completed turn.
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
			requireThat(next.completed(), "completed").isTrue();
		}
	}

	/**
	 * Verifies the bridge reports an incomplete turn when its input reaches EOF without a completion notification,
	 * and waits for the supplied process to exit before reading its exit status.
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
			"\"text\":" + JsonMapper.builder().build().writeValueAsString(text) + "}," +
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
	 * Supplies protocol responses to {@link CodexProcess}. Startup, turn-start and interrupt response shapes follow
	 * OpenAI's codex-cli 0.147.0
	 * <a href="https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/README.md">app-server README</a>
	 * (Initialization, Lifecycle Overview, and Interrupt an active turn). Notifications retain the fields consumed
	 * by the bridge. Root/child completion fields appear in {@code optimizer/codex-children.jsonl}
	 * (codex-cli 0.159.1); agent-message fields follow that README's Events and Items sections.
	 * Identifiers and message text are controlled test inputs. Withholding a response lets the caller choose when to
	 * deliver it; it makes no claim about harness timing. EOF-before-exit uses {@link FakeHarnessCli}'s generic process
	 * lifecycle model.
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
			return start("", "");
		}

		/**
		 * Starts a runner process over this app-server under a profile and creates its root thread.
		 *
		 * @param model the model to select, or empty for Codex's default
		 * @param effort the reasoning effort to select, or empty for Codex's default
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start(String model, String effort) throws IOException
		{
			CodexProcess result = new CodexProcess(process, new TestHarnessRunnerScope(), false);
			result.initialize(PROJECT, model, "", effort);
			return result;
		}

		/**
		 * Returns the requests received so far that call a method.
		 *
		 * @param method the method
		 * @return each request's parameters
		 */
		private List<JsonNode> requests(String method)
		{
			return process.requests().stream().map(json::readTree).
				filter(request -> request.path("method").asString("").equals(method)).
				map(request -> request.path("params")).
				toList();
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
				case "initialize" -> process.emit("{\"id\":" + id + ",\"result\":{\"userAgent\":\"codex/0.147.0\"," +
					"\"codexHome\":\"/codex-home\",\"platformFamily\":\"unix\",\"platformOs\":\"linux\"}}");
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
